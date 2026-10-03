from pathlib import Path
from typing import cast
from uuid import UUID

import pytest
from pydantic import ValidationError

from vcut_workers.application.clip_generation import GenerateClipUseCase
from vcut_workers.application.ports import MediaProcessor
from vcut_workers.contracts.clip_generation import (
    AspectRatio,
    CaptionCue,
    CaptionPreset,
    CaptionStyle,
    ClipGenerationCommand,
    ClipGenerationResult,
    ClipStatus,
)
from vcut_workers.domain.clip_generation import (
    CaptionTrack,
    ClipComposition,
    caption_style_for_preset,
)
from vcut_workers.domain.media import VideoMetadata
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage

CLIP_ID = UUID("11111111-1111-4111-8111-111111111111")
USER_ID = UUID("22222222-2222-4222-8222-222222222222")
PROJECT_ID = UUID("33333333-3333-4333-8333-333333333333")
VIDEO_ID = UUID("44444444-4444-4444-8444-444444444444")


def command(**overrides: object) -> ClipGenerationCommand:
    payload: dict[str, object] = {
        "clipId": CLIP_ID,
        "userId": USER_ID,
        "projectId": PROJECT_ID,
        "videoId": VIDEO_ID,
        "pipelineVersion": 3,
        "editVersion": 2,
        "sourceObjectKey": "users/source/video.mp4",
        "outputObjectKey": "users/clips/clip-v2.mp4",
        "startSeconds": 2.0,
        "endSeconds": 5.0,
        "aspectRatio": "9:16",
        "captionPreset": "KARAOKE",
        "captionStyle": {
            "fontFamily": "DejaVu Sans",
            "fontSize": 48,
            "fontWeight": 700,
            "textColor": "#FFFFFF",
            "backgroundColor": "#000000",
            "backgroundOpacity": 0.8,
            "position": "CENTER",
            "animation": "KARAOKE",
        },
        "captionCues": [
            {"sequence": 0, "text": "Olá, mundo!", "startSeconds": 0.2, "endSeconds": 1.2},
            {"sequence": 1, "text": "ação segura", "startSeconds": 1.5, "endSeconds": 2.8},
        ],
    }
    payload.update(overrides)
    return ClipGenerationCommand.model_validate(payload)


def test_command_serializes_the_api_camel_case_contract() -> None:
    serialized = command().model_dump(by_alias=True, mode="json")

    assert serialized["clipId"] == str(CLIP_ID)
    assert serialized["pipelineVersion"] == 3
    assert serialized["captionCues"][0]["startSeconds"] == 0.2
    assert set(serialized) == {
        "clipId",
        "userId",
        "projectId",
        "videoId",
        "pipelineVersion",
        "editVersion",
        "sourceObjectKey",
        "outputObjectKey",
        "startSeconds",
        "endSeconds",
        "aspectRatio",
        "captionPreset",
        "captionStyle",
        "captionCues",
    }


@pytest.mark.parametrize(
    "overrides",
    [
        {"aspectRatio": "1:1"},
        {"startSeconds": 5.0, "endSeconds": 5.0},
        {"editVersion": 0},
        {"sourceObjectKey": "$(ffmpeg)"},
        {"outputObjectKey": "clips/../output.mp4"},
        {"captionCues": [{"sequence": 0, "text": "fora", "startSeconds": 0, "endSeconds": 3.1}]},
        {
            "captionCues": [
                {"sequence": 1, "text": "a", "startSeconds": 0, "endSeconds": 1},
                {"sequence": 1, "text": "b", "startSeconds": 1, "endSeconds": 2},
            ]
        },
        {"unexpected": "field"},
    ],
)
def test_command_rejects_invalid_or_untrusted_values(overrides: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        command(**overrides)


def test_caption_style_validates_font_weight_and_hex_colors() -> None:
    with pytest.raises(ValidationError):
        CaptionStyle(font="font;drawtext", font_weight=700)
    with pytest.raises(ValidationError):
        CaptionStyle(font_weight=750)
    with pytest.raises(ValidationError):
        CaptionStyle(color="#GGGGGG")


def test_all_caption_presets_are_valid_and_serializable() -> None:
    for preset in CaptionPreset:
        style = caption_style_for_preset(preset)
        serialized = style.model_dump(by_alias=True)
        assert serialized["fontSize"] > 0
        assert serialized["fontWeight"] >= 100
        assert serialized["textColor"].startswith("#")
        assert serialized["backgroundColor"].startswith("#")


def test_caption_track_rejects_non_monotonic_cues() -> None:
    with pytest.raises(ValidationError):
        CaptionTrack(
            duration_seconds=4,
            cues=(
                CaptionCue(sequence=0, text="one", start_seconds=1, end_seconds=2),
                CaptionCue(sequence=1, text="two", start_seconds=1.5, end_seconds=3),
            ),
        )


class FakeMediaProcessor:
    def __init__(self) -> None:
        self.compositions: list[object] = []
        self.source_metadata = VideoMetadata(
            container="mp4",
            duration_seconds=10,
            width=1920,
            height=1080,
            frame_rate=30,
            has_audio=True,
            video_codec="h264",
            audio_codec="aac",
        )

    def probe(self, source: Path) -> VideoMetadata:
        if source.name == "source":
            return self.source_metadata
        return VideoMetadata(
            container="mp4",
            duration_seconds=3,
            width=1080,
            height=1920,
            frame_rate=30,
            has_audio=True,
            video_codec="h264",
            audio_codec="aac",
        )

    def compose(self, source: Path, destination: Path, composition: object) -> VideoMetadata:
        del source
        self.compositions.append(composition)
        destination.write_bytes(b"rendered")
        return self.probe(destination)


def test_generation_downloads_composes_probes_and_uploads_idempotently() -> None:
    storage = InMemoryObjectStorage()
    storage.put("users/source/video.mp4", b"source", "video/mp4")
    processor = FakeMediaProcessor()
    use_case = GenerateClipUseCase(storage, cast(MediaProcessor, processor))

    first = use_case.execute(command())
    second = use_case.execute(command())

    assert first.status is ClipStatus.READY
    assert first.aspect_ratio is AspectRatio.VERTICAL
    assert first.output_object_key == "users/clips/clip-v2.mp4"
    assert second == first
    assert len(processor.compositions) == 1
    composition = cast(ClipComposition, processor.compositions[0])
    assert composition.caption_style.font_size == 48
    assert composition.caption_style.animation.value == "KARAOKE"
    assert composition.caption_track.cues[0].text == "Olá, mundo!"
    assert storage.exists("users/clips/clip-v2.mp4")


def test_failed_result_requires_a_typed_error() -> None:
    result = ClipGenerationResult(
        clip_id=CLIP_ID,
        edit_version=2,
        status=ClipStatus.FAILED,
        duration_seconds=0,
        width=0,
        height=0,
        aspect_ratio=AspectRatio.HORIZONTAL,
        error_code="SOURCE_NOT_FOUND",
        error_message="source media was not found",
    )

    assert result.status is ClipStatus.FAILED
    assert result.model_dump(by_alias=True)["errorCode"] == "SOURCE_NOT_FOUND"
