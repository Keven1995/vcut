import re
from enum import StrEnum
from math import isfinite

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator


class AspectRatio(StrEnum):
    VERTICAL = "9:16"
    HORIZONTAL = "16:9"


class ClipStatus(StrEnum):
    READY = "READY"
    FAILED = "FAILED"


class CaptionPreset(StrEnum):
    MINIMAL = "MINIMAL"
    BOLD = "BOLD"
    KARAOKE = "KARAOKE"
    PODCAST = "PODCAST"
    GAMING = "GAMING"
    TIKTOK = "TIKTOK"


class CaptionPosition(StrEnum):
    TOP = "TOP"
    CENTER = "CENTER"
    BOTTOM = "BOTTOM"


class CaptionAnimation(StrEnum):
    NONE = "NONE"
    FADE = "FADE"
    POP = "POP"
    KARAOKE = "KARAOKE"


_HEX_COLOR = re.compile(r"^#[0-9A-Fa-f]{6}$")
_FONT_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9 ._-]{0,63}$")
_OBJECT_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]*$")


def validate_object_key(value: str) -> str:
    if (
        not value
        or not _OBJECT_KEY.fullmatch(value)
        or "//" in value
        or any(part in {".", ".."} for part in value.split("/"))
    ):
        raise ValueError("object key must be a safe relative storage path")
    return value


class CaptionStyle(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    font: str = Field(
        default="DejaVu Sans",
        validation_alias=AliasChoices("font", "fontFamily"),
        serialization_alias="fontFamily",
        min_length=1,
        max_length=64,
    )
    font_size: int = Field(
        default=48,
        validation_alias=AliasChoices("font_size", "fontSize"),
        serialization_alias="fontSize",
        ge=8,
        le=256,
    )
    font_weight: int = Field(
        default=700,
        validation_alias=AliasChoices("font_weight", "fontWeight"),
        serialization_alias="fontWeight",
        ge=100,
        le=900,
    )
    color: str = Field(
        default="#FFFFFF",
        validation_alias=AliasChoices("color", "textColor"),
        serialization_alias="textColor",
        min_length=7,
        max_length=7,
    )
    highlight_color: str = Field(
        default="#FFFF00",
        validation_alias=AliasChoices("highlight_color", "highlightColor"),
        serialization_alias="highlightColor",
        min_length=7,
        max_length=7,
    )
    background_color: str = Field(
        default="#000000",
        validation_alias=AliasChoices("background_color", "backgroundColor", "background"),
        serialization_alias="backgroundColor",
        min_length=7,
        max_length=7,
    )
    background_opacity: float = Field(
        default=0.8,
        validation_alias=AliasChoices("background_opacity", "backgroundOpacity"),
        serialization_alias="backgroundOpacity",
        ge=0,
        le=1,
    )
    position: CaptionPosition = CaptionPosition.BOTTOM
    animation: CaptionAnimation = CaptionAnimation.NONE

    @field_validator("font")
    @classmethod
    def validate_font(cls, value: str) -> str:
        if not _FONT_NAME.fullmatch(value) or "drawtext" in value.lower():
            raise ValueError("font must be a safe font family name")
        return value

    @field_validator("font_weight")
    @classmethod
    def validate_font_weight(cls, value: int) -> int:
        if value % 100 != 0:
            raise ValueError("font_weight must be a multiple of 100")
        return value

    @field_validator("color", "highlight_color", "background_color")
    @classmethod
    def validate_hex_color(cls, value: str) -> str:
        if _HEX_COLOR.fullmatch(value) is None:
            raise ValueError("colors must be six-digit hexadecimal values")
        return value.upper()


class CaptionCue(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    sequence: int = Field(ge=0)
    text: str = Field(min_length=1, max_length=1_000)
    start_seconds: float = Field(
        validation_alias=AliasChoices("start_seconds", "startSeconds"),
        serialization_alias="startSeconds",
        ge=0,
    )
    end_seconds: float = Field(
        validation_alias=AliasChoices("end_seconds", "endSeconds"),
        serialization_alias="endSeconds",
        gt=0,
    )

    @field_validator("text")
    @classmethod
    def validate_text(cls, value: str) -> str:
        if not value.strip() or "\x00" in value:
            raise ValueError("caption text must be non-empty and must not contain NUL")
        return value

    @model_validator(mode="after")
    def validate_interval(self) -> "CaptionCue":
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("caption timestamps must be finite")
        if self.end_seconds <= self.start_seconds:
            raise ValueError("caption cue end must be greater than start")
        return self


class CaptionTrack(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    cues: tuple[CaptionCue, ...] = Field(default_factory=tuple, min_length=0)
    duration_seconds: float | None = Field(
        default=None,
        validation_alias=AliasChoices("duration_seconds", "durationSeconds"),
        serialization_alias="durationSeconds",
        gt=0,
    )

    @model_validator(mode="after")
    def validate_order_and_range(self) -> "CaptionTrack":
        if self.duration_seconds is not None and not isfinite(self.duration_seconds):
            raise ValueError("caption track duration must be finite")

        previous_sequence: int | None = None
        previous_start = 0.0
        previous_end = 0.0
        for cue in self.cues:
            if previous_sequence is not None and cue.sequence <= previous_sequence:
                raise ValueError("caption cue sequences must be strictly increasing")
            if cue.start_seconds < previous_start or cue.end_seconds < previous_end:
                raise ValueError("caption timestamps must be monotonic")
            if previous_sequence is not None and cue.start_seconds < previous_end:
                raise ValueError("caption cues must not overlap")
            if self.duration_seconds is not None and cue.end_seconds > self.duration_seconds:
                raise ValueError("caption cue is outside the clip interval")
            previous_sequence = cue.sequence
            previous_start = cue.start_seconds
            previous_end = cue.end_seconds
        return self


class ClipComposition(BaseModel):
    """Validated, provider-neutral instructions for one deterministic render."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    start_seconds: float = Field(
        validation_alias=AliasChoices("start_seconds", "startSeconds"),
        serialization_alias="startSeconds",
        ge=0,
    )
    end_seconds: float = Field(
        validation_alias=AliasChoices("end_seconds", "endSeconds"),
        serialization_alias="endSeconds",
        gt=0,
    )
    aspect_ratio: AspectRatio = Field(
        validation_alias=AliasChoices("aspect_ratio", "aspectRatio"),
        serialization_alias="aspectRatio",
    )
    caption_track: CaptionTrack = Field(
        validation_alias=AliasChoices("caption_track", "captionTrack"),
        serialization_alias="captionTrack",
    )
    caption_style: CaptionStyle = Field(
        validation_alias=AliasChoices("caption_style", "captionStyle"),
        serialization_alias="captionStyle",
    )
    width: int = Field(gt=0)
    height: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_composition(self) -> "ClipComposition":
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("composition timestamps must be finite")
        if self.end_seconds <= self.start_seconds:
            raise ValueError("composition end must be greater than start")
        if self.caption_track.duration_seconds is None:
            raise ValueError("caption track duration is required")
        if self.caption_track.duration_seconds != self.duration_seconds:
            raise ValueError("caption track duration must match composition duration")
        expected_vertical = self.aspect_ratio is AspectRatio.VERTICAL
        if expected_vertical and self.width * 16 != self.height * 9:
            raise ValueError("vertical composition dimensions must be 9:16")
        if not expected_vertical and self.width * 9 != self.height * 16:
            raise ValueError("horizontal composition dimensions must be 16:9")
        return self

    @property
    def duration_seconds(self) -> float:
        return self.end_seconds - self.start_seconds


_CAPTION_PRESETS: dict[CaptionPreset, CaptionStyle] = {
    CaptionPreset.MINIMAL: CaptionStyle(
        font="DejaVu Sans",
        font_size=40,
        font_weight=400,
        color="#FFFFFF",
        highlight_color="#FFFFFF",
        background_color="#000000",
        position=CaptionPosition.BOTTOM,
        animation=CaptionAnimation.NONE,
    ),
    CaptionPreset.BOLD: CaptionStyle(
        font="DejaVu Sans",
        font_size=52,
        font_weight=800,
        color="#FFFFFF",
        highlight_color="#FFFF00",
        background_color="#000000",
        position=CaptionPosition.CENTER,
        animation=CaptionAnimation.FADE,
    ),
    CaptionPreset.KARAOKE: CaptionStyle(
        font="DejaVu Sans",
        font_size=48,
        font_weight=700,
        color="#FFFFFF",
        highlight_color="#00E5FF",
        background_color="#000000",
        position=CaptionPosition.CENTER,
        animation=CaptionAnimation.KARAOKE,
    ),
    CaptionPreset.PODCAST: CaptionStyle(
        font="DejaVu Sans",
        font_size=42,
        font_weight=700,
        color="#FFFFFF",
        highlight_color="#FFFFFF",
        background_color="#111111",
        position=CaptionPosition.BOTTOM,
        animation=CaptionAnimation.FADE,
    ),
    CaptionPreset.GAMING: CaptionStyle(
        font="DejaVu Sans",
        font_size=44,
        font_weight=800,
        color="#FFFFFF",
        highlight_color="#00FF66",
        background_color="#101010",
        position=CaptionPosition.TOP,
        animation=CaptionAnimation.KARAOKE,
    ),
    CaptionPreset.TIKTOK: CaptionStyle(
        font="DejaVu Sans",
        font_size=48,
        font_weight=800,
        color="#FFFFFF",
        highlight_color="#FFEA00",
        background_color="#000000",
        position=CaptionPosition.CENTER,
        animation=CaptionAnimation.KARAOKE,
    ),
}


def caption_style_for_preset(preset: CaptionPreset) -> CaptionStyle:
    return _CAPTION_PRESETS[CaptionPreset(preset)]


__all__ = [
    "AspectRatio",
    "CaptionAnimation",
    "CaptionCue",
    "CaptionPosition",
    "CaptionPreset",
    "CaptionStyle",
    "CaptionTrack",
    "ClipComposition",
    "ClipStatus",
    "caption_style_for_preset",
    "validate_object_key",
]
