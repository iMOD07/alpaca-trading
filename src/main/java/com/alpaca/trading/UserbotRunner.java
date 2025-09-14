package com.alpaca.trading;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

@Component
public class UserbotRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(UserbotRunner.class);

    @Value("${telegram.api-id}")   private int apiId;
    @Value("${telegram.api-hash}") private String apiHash;
    @Value("${telegram.phone}")    private String phoneNumber;
    @Value("${telegram.session-dir:tdlight-session}") private String sessionDir;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    @Override
    public void run(String... args) throws Exception {
        // 1) تهيئة TDLight/TDLib
        Init.init();
        Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());

        // 2) إعدادات TDLib
        clientFactory = new SimpleTelegramClientFactory();
        APIToken token = new APIToken(apiId, apiHash);
        TDLibSettings settings = TDLibSettings.create(token);

        Path base = Paths.get(sessionDir);
        settings.setDatabaseDirectoryPath(base.resolve("db"));
        settings.setDownloadedFilesDirectoryPath(base.resolve("downloads"));
        settings.setDeviceModel("SpringBoot-Userbot");
        settings.setSystemLanguageCode("en");
        settings.setApplicationVersion("1.0.0");

        // 3) بناء العميل ومعالجات التحديثات
        SimpleTelegramClientBuilder builder = clientFactory.builder(settings);

        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, update -> {
            var st = update.authorizationState;
            if (st instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                log.info("Waiting for phone number…");
            } else if (st instanceof TdApi.AuthorizationStateWaitCode) {
                log.info("Waiting for login code (SMS/Telegram)...");
            } else if (st instanceof TdApi.AuthorizationStateReady) {
                log.info("Authorized ✅");
            } else if (st instanceof TdApi.AuthorizationStateClosed) {
                log.info("Client closed.");
            }
        });

        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> {
            TdApi.Message msg = update.message;
            if (msg.isOutgoing) return;

            String text = "";
            if (msg.content instanceof TdApi.MessageText t) {
                text = t.text.text == null ? "" : t.text.text.trim();
            }

            if ("/ping".equalsIgnoreCase(text)) {
                // 👇 IMPORTANT FIX #1:
                // InputMessageText: المَعْلَمة الثانية الآن LinkPreviewOptions وليس boolean
                TdApi.FormattedText ft = new TdApi.FormattedText("pong", new TdApi.TextEntity[0]);
                TdApi.InputMessageText payload = new TdApi.InputMessageText(
                        ft,
                        /* linkPreviewOptions */ null,  // لا نحتاج معاينة روابط
                        /* clearDraft */ true
                );

                // 👇 IMPORTANT FIX #2:
                // SendMessage: الحقل الثالث الآن InputMessageReplyTo وليس int
                TdApi.SendMessage req = new TdApi.SendMessage(
                        msg.chatId,
                        /* messageThreadId */ 0,
                        /* replyTo */ null,
                        new TdApi.MessageSendOptions(),
                        /* replyMarkup */ null,
                        payload
                );

                CompletableFuture<TdApi.Message> f = client.send(req);
                f.whenComplete((ok, err) -> {
                    if (err != null) {
                        log.error("Failed to send pong", err);
                    } else {
                        log.info("Pong sent to chat {}", ok.chatId);
                    }
                });
            }
        });

        // 4) دخول بحساب شخصي (userbot) — لو احتاج كود/2FA بيطلبه في الطرفية
        client = builder.build(AuthenticationSupplier.user(phoneNumber));

        // 5) إغلاق نظيف
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (client != null) client.close();
                if (clientFactory != null) clientFactory.close();
            } catch (Exception e) {
                log.warn("Error on shutdown", e);
            }
        }));

        log.info("Userbot started. Listening for updates…");
    }
}
