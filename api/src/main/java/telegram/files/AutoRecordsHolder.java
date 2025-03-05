package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class AutoRecordsHolder {
    private final Log log = LogFactory.get();

    private final SettingAutoRecords autoRecords = new SettingAutoRecords();

    private final List<Consumer<List<SettingAutoRecords.Item>>> onRemoveListeners = new ArrayList<>();

    private volatile boolean initialized = false;

    public static final AutoRecordsHolder INSTANCE = new AutoRecordsHolder();

    private AutoRecordsHolder() {
    }

    public SettingAutoRecords autoRecords() {
        return autoRecords;
    }

    public void registerOnRemoveListener(Consumer<List<SettingAutoRecords.Item>> onRemove) {
        onRemoveListeners.add(onRemove);
    }

    public synchronized Future<Void> init() {
        if (initialized) {
            return Future.succeededFuture();
        }
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.autoDownload)
                .onSuccess(settingAutoRecords -> {
                    initialized = true;
                    if (settingAutoRecords == null) {
                        return;
                    }
                    settingAutoRecords.items.forEach(item -> TelegramVerticles.get(item.telegramId)
                            .ifPresentOrElse(telegramVerticle -> {
                                if (telegramVerticle.authorized) {
                                    autoRecords.add(item);
                                } else {
                                    log.warn("Init auto records fail. Telegram verticle not authorized: %s".formatted(item.telegramId));
                                }
                            }, () -> log.warn("Init auto records fail. Telegram verticle not found: %s".formatted(item.telegramId))));
                })
                .onFailure(e -> log.error("Init auto records failed!", e))
                .mapEmpty();
    }

    public void onAutoRecordsUpdate(SettingAutoRecords records) {
        for (SettingAutoRecords.Item item : records.items) {
            if (!autoRecords.exists(item.telegramId, item.chatId)) {
                // new enabled
                TelegramVerticles.get(item.telegramId)
                        .ifPresentOrElse(telegramVerticle -> {
                            if (telegramVerticle.authorized) {
                                autoRecords.add(item);
                                log.info("Add auto records success: %s".formatted(item.uniqueKey()));
                            } else {
                                log.warn("Add auto records fail. Telegram verticle not authorized: %s".formatted(item.telegramId));
                            }
                        }, () -> log.warn("Add auto records fail. Telegram verticle not found: %s".formatted(item.telegramId)));
            }
        }
        // remove disabled
        List<SettingAutoRecords.Item> removedItems = new ArrayList<>();
        autoRecords.items.removeIf(item -> {
            if (records.exists(item.telegramId, item.chatId)) {
                return false;
            }
            removedItems.add(item);
            log.info("Remove auto records success: %s".formatted(item.uniqueKey()));
            return true;
        });
        if (CollUtil.isNotEmpty(removedItems)) {
            onRemoveListeners.forEach(listener -> listener.accept(removedItems));
        }
    }

    public Future<Void> saveAutoRecords() {
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.autoDownload)
                .compose(settingAutoRecords -> {
                    if (settingAutoRecords == null) {
                        settingAutoRecords = new SettingAutoRecords();
                    }
                    autoRecords.items.forEach(settingAutoRecords::add);
                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.autoDownload.name(), Json.encode(settingAutoRecords));
                })
                .onFailure(e -> log.error("Save auto records failed!", e))
                .mapEmpty();
    }
}
