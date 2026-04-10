package com.copytrading.logs;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class CopyLogController {

    private final CopyLogRepository copyLogs;

    public CopyLogController(CopyLogRepository copyLogs) {
        this.copyLogs = copyLogs;
    }

    @GetMapping("/api/v1/copy/logs")
    public Mono<Map<String, Object>> getAllCopyLogs() {
        return copyLogs.findAll().collectList()
                .map(list -> Map.<String, Object>of("logs", list));
    }
}
