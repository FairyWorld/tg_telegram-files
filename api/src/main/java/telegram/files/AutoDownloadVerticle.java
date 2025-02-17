package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple2;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class AutoDownloadVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final int DEFAULT_LIMIT = 5;

    private static final int HISTORY_SCAN_INTERVAL = 2 * 60 * 1000;

    private static final int MAX_HISTORY_SCAN_TIME = 10 * 1000;

    private static final int MAX_WAITING_LENGTH = 30;

    private static final int DOWNLOAD_INTERVAL = 10 * 1000;

    private static final List<String> DEFAULT_FILE_TYPE_ORDER = List.of("photo", "video", "audio", "file");

    // telegramId -> messages
    private final Map<Long, LinkedList<TdApi.Message>> waitingDownloadMessages = new ConcurrentHashMap<>();

    private final SettingAutoRecords autoRecords;

    private int limit = DEFAULT_LIMIT;

    public AutoDownloadVerticle(AutoRecordsHolder autoRecordsHolder) {
        this.autoRecords = autoRecordsHolder.autoRecords();
        autoRecordsHolder.registerOnRemoveListener(removedItems -> removedItems.forEach(item ->
                waitingDownloadMessages.getOrDefault(item.telegramId, new LinkedList<>())
                        .removeIf(m -> m.chatId == item.chatId)));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        initAutoDownload()
                .compose(v -> this.initEventConsumer())
                .onSuccess(v -> {
                    vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL,
                            id -> autoRecords.items.forEach(auto -> addHistoryMessage(auto, System.currentTimeMillis())));
                    vertx.setPeriodic(0, DOWNLOAD_INTERVAL,
                            id -> waitingDownloadMessages.keySet().forEach(this::download));

                    log.info("""
                            Auto download verticle started!
                            |History scan interval: %s ms
                            |Download interval: %s ms
                            |Download limit: %s per telegram account!
                            |Auto chats: %s
                            """.formatted(HISTORY_SCAN_INTERVAL, DOWNLOAD_INTERVAL, limit, autoRecords.items.size()));

                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        saveAutoRecords()
                .onComplete(r -> {
                    log.info("Auto download verticle stopped!");
                    stopPromise.complete();
                });
    }

    private Future<Void> initAutoDownload() {
        return DataVerticle.settingRepository.<Integer>getByKey(SettingKey.autoDownloadLimit)
                .onSuccess(limit -> {
                    if (limit != null) {
                        this.limit = limit;
                    }
                })
                .onFailure(e -> log.error("Get Auto download limit failed!", e))
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadLimit.name()), message -> {
            log.debug("Auto download limit update: %s".formatted(message.body()));
            this.limit = Convert.toInt(message.body(), DEFAULT_LIMIT);
        });
        vertx.eventBus().consumer(EventEnum.MESSAGE_RECEIVED.address(), message -> {
            log.trace("Auto download message received: %s".formatted(message.body()));
            this.onNewMessage((JsonObject) message.body());
        });
        return Future.succeededFuture();
    }

    private Future<Void> saveAutoRecords() {
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

    private void addHistoryMessage(SettingAutoRecords.Item auto, long currentTimeMillis) {
        log.debug("Start scan history! TelegramId: %d ChatId: %d FileType: %s".formatted(auto.telegramId, auto.chatId, auto.nextFileType));
        if (System.currentTimeMillis() - currentTimeMillis > MAX_HISTORY_SCAN_TIME || isExceedLimit(auto.telegramId)) {
            log.debug("Scan history end! TelegramId: %d ChatId: %d".formatted(auto.telegramId, auto.chatId));
            return;
        }
        Tuple2<String, List<String>> rule = handleRule(auto);

        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(auto.telegramId);
        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.query = rule.v1;
        searchChatMessages.chatId = auto.chatId;
        searchChatMessages.fromMessageId = auto.nextFromMessageId;
        searchChatMessages.limit = Math.min(MAX_WAITING_LENGTH, 100);
        searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(auto.nextFileType);
        TdApi.FoundChatMessages foundChatMessages = Future.await(telegramVerticle.client.execute(searchChatMessages)
                .onFailure(r -> log.error("Search chat messages failed! TelegramId: %d ChatId: %d".formatted(auto.telegramId, auto.chatId), r))
        );
        if (foundChatMessages == null) {
            return;
        }
        if (foundChatMessages.messages.length == 0) {
            List<String> fileTypes = rule.v2;
            int nextTypeIndex = fileTypes.indexOf(auto.nextFileType) + 1;
            if (nextTypeIndex < fileTypes.size()) {
                String originalType = auto.nextFileType;
                auto.nextFileType = fileTypes.get(nextTypeIndex);
                auto.nextFromMessageId = 0;
                log.debug("%s No more %s files found! Switch to %s".formatted(auto.uniqueKey(), originalType, auto.nextFileType));
                addHistoryMessage(auto, currentTimeMillis);
            } else {
                log.debug("%s No more history files found! TelegramId: %d ChatId: %d".formatted(auto.uniqueKey(), auto.telegramId, auto.chatId));
            }
        } else {
            DataVerticle.fileRepository.getFilesByUniqueId(TdApiHelp.getFileUniqueIds(Arrays.asList(foundChatMessages.messages)))
                    .onSuccess(existFiles -> {
                        List<TdApi.Message> messages = Stream.of(foundChatMessages.messages)
                                .filter(message -> !existFiles.containsKey(TdApiHelp.getFileUniqueId(message)))
                                .toList();
                        if (CollUtil.isEmpty(messages)) {
                            auto.nextFromMessageId = foundChatMessages.nextFromMessageId;
                            addHistoryMessage(auto, currentTimeMillis);
                        } else if (addWaitingDownloadMessages(auto.telegramId, messages, false)) {
                            auto.nextFromMessageId = foundChatMessages.nextFromMessageId;
                        } else {
                            addHistoryMessage(auto, currentTimeMillis);
                        }
                    });
        }
    }

    private Tuple2<String, List<String>> handleRule(SettingAutoRecords.Item auto) {
        SettingAutoRecords.Rule rule = auto.rule;
        String query = null;
        List<String> fileTypes = DEFAULT_FILE_TYPE_ORDER;
        if (rule != null) {
            if (StrUtil.isNotBlank(rule.query)) {
                query = rule.query;
            }
            if (CollUtil.isNotEmpty(rule.fileTypes)) {
                fileTypes = rule.fileTypes;
            }
        }
        if (StrUtil.isBlank(auto.nextFileType)) {
            auto.nextFileType = fileTypes.getFirst();
        }

        return new Tuple2<>(query, fileTypes);
    }

    private boolean isExceedLimit(long telegramId) {
        List<TdApi.Message> waitingMessages = this.waitingDownloadMessages.get(telegramId);
        return getSurplusSize(telegramId) <= 0 || (waitingMessages != null && waitingMessages.size() > limit);
    }

    private int getSurplusSize(long telegramId) {
        Integer downloading = Future.await(DataVerticle.fileRepository.countByStatus(telegramId, FileRecord.DownloadStatus.downloading));
        return downloading == null ? limit : Math.max(0, limit - downloading);
    }

    private boolean addWaitingDownloadMessages(long telegramId, List<TdApi.Message> messages, boolean force) {
        if (CollUtil.isEmpty(messages)) {
            return false;
        }
        LinkedList<TdApi.Message> waitingMessages = this.waitingDownloadMessages.get(telegramId);
        if (waitingMessages == null) {
            waitingMessages = new LinkedList<>();
        }
        if (!force && waitingMessages.size() > MAX_WAITING_LENGTH) {
            return false;
        } else {
            log.debug("Add waiting download messages: %d".formatted(messages.size()));
            waitingMessages.addAll(TdApiHelp.filterUniqueMessages(messages));
        }
        this.waitingDownloadMessages.put(telegramId, waitingMessages);
        return true;
    }

    private void download(long telegramId) {
        if (CollUtil.isEmpty(waitingDownloadMessages)) {
            return;
        }
        LinkedList<TdApi.Message> messages = waitingDownloadMessages.get(telegramId);
        if (CollUtil.isEmpty(messages)) {
            return;
        }
        log.debug("Download start! TelegramId: %d size: %d".formatted(telegramId, messages.size()));
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        int surplusSize = getSurplusSize(telegramId);
        if (surplusSize <= 0) {
            return;
        }

        List<TdApi.Message> downloadMessages = IntStream.range(0, Math.min(surplusSize, messages.size()))
                .mapToObj(i -> messages.poll())
                .toList();
        downloadMessages.forEach(message -> {
            Integer fileId = TdApiHelp.getFileId(message);
            log.debug("Start download file: %s".formatted(fileId));
            telegramVerticle.startDownload(message.chatId, message.id, fileId)
                    .onSuccess(v -> log.info("Start download file success! ChatId: %d MessageId:%d FileId:%d"
                            .formatted(message.chatId, message.id, fileId))
                    )
                    .onFailure(e -> log.error("Download file failed! ChatId: %d MessageId:%d FileId:%d"
                            .formatted(message.chatId, message.id, fileId), e));
        });
        log.debug("Remaining download messages: %d".formatted(messages.size()));
    }

    private void onNewMessage(JsonObject jsonObject) {
        long telegramId = jsonObject.getLong("telegramId");
        long chatId = jsonObject.getLong("chatId");
        long messageId = jsonObject.getLong("messageId");
        autoRecords.items.stream()
                .filter(item -> item.telegramId == telegramId && item.chatId == chatId)
                .findFirst()
                .flatMap(item -> TelegramVerticles.get(telegramId))
                .ifPresent(telegramVerticle -> {
                    if (telegramVerticle.authorized) {
                        telegramVerticle.client.execute(new TdApi.GetMessage(chatId, messageId))
                                .onSuccess(message -> addWaitingDownloadMessages(telegramId, List.of(message), true))
                                .onFailure(e -> log.error("Auto download fail. Get message failed: %s".formatted(e.getMessage())));
                    }
                });
    }
}
