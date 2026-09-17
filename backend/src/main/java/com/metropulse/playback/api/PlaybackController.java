package com.metropulse.playback.api;

import com.metropulse.playback.application.PlaybackService;
import com.metropulse.playback.domain.PlaybackFrame;
import com.metropulse.playback.domain.PlaybackSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {

    private final PlaybackService playbackService;

    public PlaybackController(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaybackSession createSession(
            @Valid @RequestBody CreatePlaybackSessionRequest request,
            Principal principal
    ) {
        return playbackService.createSession(request, principal == null ? "unknown" : principal.getName());
    }

    @GetMapping("/sessions/{id}")
    public PlaybackSession session(@PathVariable long id) {
        return playbackService.findSession(id);
    }

    @GetMapping("/sessions/{id}/frames")
    public List<PlaybackFrame> frames(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "500") int limit
    ) {
        return playbackService.findFrames(id, offset, limit);
    }
}
