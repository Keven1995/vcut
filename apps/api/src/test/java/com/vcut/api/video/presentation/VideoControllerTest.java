package com.vcut.api.video.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.shared.correlation.CorrelationIdFilter;
import com.vcut.api.shared.errors.GlobalExceptionHandler;
import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.application.VideoApplicationService;
import com.vcut.api.video.domain.Video;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VideoControllerTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID VIDEO_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  private final VideoApplicationService service = mock(VideoApplicationService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new VideoController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void createsUploadIntentWithoutAcceptingClientObjectKey() throws Exception {
    Video video =
        Video.uploading(
            VIDEO_ID,
            USER_ID,
            PROJECT_ID,
            "users/111/projects/222/source/333/original.mp4",
            "original.mp4",
            "video/mp4",
            100,
            Instant.parse("2026-09-28T12:00:00Z"));
    when(service.createUploadIntent(
            eq(USER_ID), eq(PROJECT_ID), eq("original.mp4"), eq("video/mp4"), eq(100L)))
        .thenReturn(
            new VideoApplicationService.UploadIntent(
                video,
                new ObjectStorage.PresignedUpload(
                    "http://localhost:9000/vcut-local/upload",
                    Instant.parse("2026-09-28T12:15:00Z"))));

    mockMvc
        .perform(
            post("/api/projects/{projectId}/videos", PROJECT_ID)
                .with(principal())
                .contentType("application/json")
                .content(
                    "{\"filename\":\"original.mp4\",\"contentType\":\"video/mp4\",\"sizeBytes\":100}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(VIDEO_ID.toString()))
        .andExpect(jsonPath("$.status").value("UPLOADING"))
        .andExpect(jsonPath("$.uploadUrl").value("http://localhost:9000/vcut-local/upload"));
  }

  @Test
  void confirmsUploadForAuthenticatedUser() throws Exception {
    Video video =
        Video.uploading(
                VIDEO_ID,
                USER_ID,
                PROJECT_ID,
                "users/111/projects/222/source/333/original.mp4",
                "original.mp4",
                "video/mp4",
                100,
                Instant.parse("2026-09-28T12:00:00Z"))
            .uploaded(100, "checksum", Instant.parse("2026-09-28T12:01:00Z"));
    when(service.confirmUpload(eq(USER_ID), eq(VIDEO_ID), any())).thenReturn(video);

    mockMvc
        .perform(
            post("/api/videos/{videoId}/confirm", VIDEO_ID)
                .with(principal())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UPLOADED"))
        .andExpect(jsonPath("$.actualSizeBytes").value(100));
  }

  private static RequestPostProcessor principal() {
    return request -> {
      request.setUserPrincipal(() -> USER_ID.toString());
      return request;
    };
  }
}
