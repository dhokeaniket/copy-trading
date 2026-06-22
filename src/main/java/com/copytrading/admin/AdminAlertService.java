package com.copytrading.admin;

import com.copytrading.notification.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AdminAlertService {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertService.class);

    private final TelegramService telegramService;
    private final String adminChannelId;

    public AdminAlertService(TelegramService telegramService,
                             @Value("${telegram.admin-channel-id:}") String adminChannelId) {
        this.telegramService = telegramService;
        this.adminChannelId = adminChannelId;
    }

    public void sendAdminAlert(String subject, String details) {
        if (adminChannelId == null || adminChannelId.isBlank()) {
            log.warn("Admin Telegram channel not configured. Dropping alert: {}", subject);
            return;
        }

        String msg = "🚨 <b>ADMIN ALERT: " + subject + "</b>\n"
                   + "<blockquote>" + details + "</blockquote>\n"
                   + "<i>Time: " + java.time.Instant.now() + "</i>";

        telegramService.sendMessage(adminChannelId, msg).subscribe();
    }
}
