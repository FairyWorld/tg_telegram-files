package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import telegram.files.repository.SettingRecord;
import telegram.files.repository.TelegramRecord;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class HttpVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    // session id -> ws handler id
    private static final Map<String, String> clients = new ConcurrentHashMap<>();

    private static final List<TelegramVerticle> telegramVerticles = new ArrayList<>();

    // session id -> telegram verticle
    private final Map<String, TelegramVerticle> sessionTelegramVerticles = new ConcurrentHashMap<>();

    private AutoDownloadVerticle autoDownloadVerticle;

    private static final String SESSION_COOKIE_NAME = "tf";

    @Override
    public void start(Promise<Void> startPromise) {
        initHttpServer()
                .compose(r -> initTelegramVerticles())
                .compose(r -> initAutoDownloadVerticle())
                .onSuccess(startPromise::complete)
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop() {
        log.info("Http verticle stopped!");
    }

    public Future<Void> initHttpServer() {
        int port = config().getInteger("http.port", 8080);
        HttpServerOptions options = new HttpServerOptions()
                .setLogActivity(true)
                .setRegisterWebSocketWriteHandlers(true)
                .setMaxWebSocketMessageSize(1024 * 1024)
                .setIdleTimeout(60)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setPort(port);

        return vertx.createHttpServer(options)
                .requestHandler(initRouter())
                .listen()
                .onSuccess(server -> log.info("API server started on port " + port))
                .onFailure(err -> log.error("Failed to start API server: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public Router initRouter() {
        Router router = Router.router(vertx);

        SessionStore sessionStore = LocalSessionStore.create(vertx, SESSION_COOKIE_NAME);
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
                .setSessionCookieName(SESSION_COOKIE_NAME);
        if (Config.isProd()) {
            sessionHandler
                    .setCookieSameSite(CookieSameSite.STRICT);
        } else {
            sessionHandler
                    .setCookieSameSite(CookieSameSite.NONE)
                    .setCookieSecureFlag(true);
        }
        router.route()
                .handler(sessionHandler)
                .handler(BodyHandler.create());

        if (!Config.isProd()) {
            router.route()
                    .handler(CorsHandler.create()
                            .addRelativeOrigin("http://localhost:3000")
                            .allowedMethod(HttpMethod.GET)
                            .allowedMethod(HttpMethod.POST)
                            .allowedMethod(HttpMethod.PUT)
                            .allowedMethod(HttpMethod.DELETE)
                            .allowedMethod(HttpMethod.OPTIONS)
                            .allowCredentials(true)
                            .allowedHeader("Access-Control-Request-Method")
                            .allowedHeader("Access-Control-Allow-Credentials")
                            .allowedHeader("Access-Control-Allow-Origin")
                            .allowedHeader("Access-Control-Allow-Headers")
                            .allowedHeader("Content-Type")
                    );
        }

        HealthChecks hc = HealthChecks.create(vertx);
        hc.register("http-server", Promise::complete);

        router.get("/").handler(ctx -> ctx.response().end("Hello World!"));
        router.get("/health").handler(HealthCheckHandler.createWithHealthChecks(hc));
        router.get("/version").handler(ctx -> ctx.json(new JsonObject().put("version", Start.VERSION)));
        router.route("/ws").handler(this::handleWebSocket);

        router.get("/settings").handler(this::handleSettings);
        router.post("/settings/create").handler(this::handleSettingsCreate);

        router.post("/telegram/create").handler(this::handleTelegramCreate);
        router.post("/telegram/:telegramId/delete").handler(this::handleTelegramDelete);
        router.post("/telegram/api/:method").handler(this::handleTelegramApi);
        router.get("/telegrams").handler(this::handleTelegrams);
        router.get("/telegram/:telegramId/chats").handler(this::handleTelegramChats);
        router.get("/telegram/:telegramId/chat/:chatId/files").handler(this::handleTelegramFiles);
        router.get("/telegram/:telegramId/chat/:chatId/files/count").handler(this::handleTelegramFilesCount);
        router.get("/telegram/:telegramId/download-statistics").handler(this::handleTelegramDownloadStatistics);
        router.post("/telegrams/change").handler(this::handleTelegramChange);
        router.post("/telegram/:telegramId/toggle-proxy").handler(this::handleTelegramToggleProxy);
        router.get("/telegram/:telegramId/ping").handler(this::handleTelegramPing);

        router.get("/file/preview").handler(this::handleFilePreview);
        router.post("/file/start-download").handler(this::handleFileStartDownload);
        router.post("/file/start-download-multiple").handler(this::handleFileStartDownloadMultiple);
        router.post("/file/cancel-download").handler(this::handleFileCancelDownload);
        router.post("/file/toggle-pause-download").handler(this::handleFileTogglePauseDownload);
        router.post("/file/auto-download").handler(this::handleAutoDownload);

        router.route()
                .failureHandler(ctx -> {
                    int statusCode = ctx.statusCode();
                    if (statusCode < 500) {
                        if (ctx.response().ended()) {
                            return;
                        }
                        ctx.response().setStatusCode(statusCode).end();
                        return;
                    }
                    Throwable throwable = ctx.failure();
                    log.error("route: %s statusCode: %d".formatted(
                            ctx.currentRoute().getName(),
                            statusCode), throwable);
                    HttpServerResponse response = ctx.response();
                    response.setStatusCode(statusCode)
                            .putHeader("Content-Type", "application/json")
                            .end(JsonObject.of("error", throwable == null ? "☹️Sorry! Not today." : throwable.getMessage()).encode());
                });
        return router;
    }

    public Future<Void> initTelegramVerticles() {
        return DataVerticle.telegramRepository.getAll()
                .compose(telegramRecords -> {
                    List<String> verifiedPath = telegramRecords.stream().map(TelegramRecord::rootPath).toList();
                    File telegramRoot = FileUtil.file(Config.TELEGRAM_ROOT);
                    List<String> uncertifiedPaths = FileUtil.loopFiles(telegramRoot, 1, null)
                            .stream()
                            .filter(f -> f.isDirectory() && !verifiedPath.contains(f.getAbsolutePath()))
                            .map(File::getAbsolutePath)
                            .toList();
                    List<Future<String>> futures = new ArrayList<>();
                    for (TelegramRecord telegramRecord : telegramRecords) {
                        TelegramVerticle telegramVerticle = new TelegramVerticle(telegramRecord);
                        if (!telegramVerticle.check()) {
                            continue;
                        }
                        telegramVerticles.add(telegramVerticle);
                        futures.add(vertx.deployVerticle(telegramVerticle));
                    }
                    if (CollUtil.isNotEmpty(uncertifiedPaths)) {
                        for (String uncertifiedPath : uncertifiedPaths) {
                            TelegramVerticle telegramVerticle = new TelegramVerticle(uncertifiedPath);
                            if (!telegramVerticle.check()) {
                                continue;
                            }
                            telegramVerticles.add(telegramVerticle);
                            futures.add(vertx.deployVerticle(telegramVerticle));
                        }
                    }
                    return Future.all(futures);
                })
                .onSuccess(r -> log.info("Successfully deployed %d telegram verticles".formatted(r.size())))
                .onFailure(err -> log.error("Failed to deploy telegram verticles: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public Future<Void> initAutoDownloadVerticle() {
        autoDownloadVerticle = new AutoDownloadVerticle();
        return vertx.deployVerticle(autoDownloadVerticle, new DeploymentOptions()
                        .setThreadingModel(ThreadingModel.VIRTUAL_THREAD)
                )
                .mapEmpty();
    }

    private void handleWebSocket(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        ctx.request().toWebSocket()
                .onSuccess(ws -> {
                    log.debug("Upgraded to WebSocket. SessionId: %s".formatted(sessionId));
                    clients.put(sessionId, ws.textHandlerID());

                    long timerId = vertx.setPeriodic(30000, id -> {
                        if (!ws.isClosed()) {
                            ws.writePing(Buffer.buffer("👀"));
                            log.debug("Ping Client: %s".formatted(sessionId));
                        }
                    });

                    ws.exceptionHandler(throwable -> log.error("WebSocket error: %s".formatted(throwable.getMessage())));
                    ws.closeHandler(e -> {
                        clients.remove(sessionId);
                        vertx.cancelTimer(timerId);
                        log.debug("WebSocket closed. SessionId: %s".formatted(sessionId));
                    });

                    ws.textMessageHandler(text -> {
                        log.debug("Received WebSocket message: " + text);
                    });
                })
                .onFailure(err -> log.warn("Failed to upgrade to WebSocket: %s".formatted(err.getMessage())));
    }

    private void handleSettingsCreate(RoutingContext ctx) {
        JsonObject object = ctx.body().asJsonObject();
        if (CollUtil.isEmpty(object)) {
            ctx.fail(400);
            return;
        }

        Future.all(object.stream()
                        .map(setting -> DataVerticle.settingRepository.createOrUpdate(setting.getKey(),
                                Convert.toStr(setting.getValue(), "")))
                        .toList())
                .map(CompositeFuture::<SettingRecord>list)
                .onSuccess(records -> {
                    records.forEach(record ->
                            vertx.eventBus().publish(EventEnum.SETTING_UPDATE.address(record.key()), record.value()));
                    ctx.end();
                })
                .onFailure(ctx::fail);
    }

    private void handleSettings(RoutingContext ctx) {
        String keys = ctx.request().getParam("keys");
        if (StrUtil.isBlank(keys)) {
            ctx.fail(400);
            return;
        }
        DataVerticle.settingRepository
                .getByKeys(Arrays.asList(keys.split(",")))
                .onSuccess(settings -> {
                    JsonObject object = new JsonObject();
                    for (SettingRecord record : settings) {
                        object.put(record.key(), record.value());
                    }
                    ctx.json(object);
                })
                .onFailure(ctx::fail);
    }

    private void handleTelegramCreate(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        if (telegramVerticle != null && !telegramVerticle.authorized) {
            ctx.json(new JsonObject()
                    .put("id", telegramVerticle.getId())
                    .put("lastState", telegramVerticle.lastAuthorizationState)
            );
            return;
        }
        JsonObject jsonObject = ctx.body().asJsonObject();
        String proxyName = jsonObject.getString("proxyName");

        TelegramVerticle newTelegramVerticle = new TelegramVerticle(DataVerticle.telegramRepository.getRootPath());
        newTelegramVerticle.bindHttpSession(sessionId);
        newTelegramVerticle.setProxy(proxyName);
        sessionTelegramVerticles.put(sessionId, newTelegramVerticle);
        telegramVerticles.add(newTelegramVerticle);
        vertx.deployVerticle(newTelegramVerticle)
                .onSuccess(id -> ctx.json(new JsonObject()
                        .put("id", newTelegramVerticle.getId())
                        .put("lastState", newTelegramVerticle.lastAuthorizationState)
                ))
                .onFailure(ctx::fail);
    }

    private void handleTelegramDelete(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        Optional<TelegramVerticle> telegramVerticleOptional = getTelegramVerticle(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            ctx.fail(404);
            return;
        }
        TelegramVerticle telegramVerticle = telegramVerticleOptional.get();
        telegramVerticle.close(true)
                .onSuccess(r -> {
                    telegramVerticles.remove(telegramVerticle);
                    sessionTelegramVerticles.entrySet().removeIf(e -> e.getValue().equals(telegramVerticle));
                    ctx.end();
                });
    }

    private void handleTelegrams(RoutingContext ctx) {
        Boolean authorized = Convert.toBool(ctx.request().getParam("authorized"));
        Future.all(telegramVerticles.stream()
                        .filter(c -> authorized == null || c.authorized == authorized)
                        .map(TelegramVerticle::getTelegramAccount)
                        .toList()
                )
                .map(CompositeFuture::list)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChats(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        String query = ctx.request().getParam("query");
        String chatId = ctx.request().getParam("chatId");
        getTelegramVerticle(telegramId)
                .ifPresentOrElse(telegramVerticle -> {
                    telegramVerticle.getChats(Convert.toLong(chatId), query)
                            .onSuccess(ctx::json)
                            .onFailure(ctx::fail);
                }, () -> ctx.fail(404));
    }

    private void handleTelegramFiles(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        long chatId = Convert.toLong(chatIdStr);
        getTelegramVerticle(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                                telegramVerticle.getChatFiles(chatId, ctx.request().params())
                                        .onSuccess(ctx::json)
                                        .onFailure(ctx::fail),
                        () -> ctx.fail(404));
    }

    private void handleTelegramFilesCount(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        long chatId = Convert.toLong(chatIdStr);
        getTelegramVerticle(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                                telegramVerticle.getChatFilesCount(chatId)
                                        .onSuccess(ctx::json)
                                        .onFailure(ctx::fail),
                        () -> ctx.fail(404));
    }

    private void handleTelegramDownloadStatistics(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        Optional<TelegramVerticle> telegramVerticleOptional = getTelegramVerticle(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            ctx.fail(404);
            return;
        }
        TelegramVerticle telegramVerticle = telegramVerticleOptional.get();

        String type = ctx.request().getParam("type");
        String timeRange = ctx.request().getParam("timeRange");
        (Objects.equals(type, "phase") ? telegramVerticle.  getDownloadStatisticsByPhase(Convert.toInt(timeRange, 1)) :
                telegramVerticle.getDownloadStatistics())
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChange(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        String telegramId = ctx.request().getParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            sessionTelegramVerticles.remove(sessionId);
            ctx.end();
        }
        getTelegramVerticle(telegramId)
                .ifPresentOrElse(telegramVerticle -> {
                    telegramVerticle.bindHttpSession(sessionId);
                    sessionTelegramVerticles.put(sessionId, telegramVerticle);
                    ctx.end();
                }, () -> ctx.fail(404));
    }

    private void handleTelegramToggleProxy(RoutingContext ctx) {
        String telegramId = ctx.request().getParam("telegramId");
        getTelegramVerticle(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.toggleProxy(ctx.body().asJsonObject())
                                .onSuccess(r -> ctx.json(JsonObject.of("proxy", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404));
    }

    private void handleTelegramPing(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        getTelegramVerticle(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.ping()
                                .onSuccess(r -> ctx.json(JsonObject.of("ping", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404)
                );
    }

    private void handleTelegramApi(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        if (method == null) {
            ctx.fail(400);
            return;
        }
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.bindHttpSession(sessionId);
        telegramVerticle.execute(method, params == null ? null : params.getMap())
                .onSuccess(code -> ctx.json(JsonObject.of("code", code)))
                .onFailure(ctx::fail);
    }

    private void handleFilePreview(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String chatId = ctx.request().getParam("chatId");
        String messageId = ctx.request().getParam("messageId");
        if (StrUtil.isBlank(chatId) || StrUtil.isBlank(messageId)) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.loadPreview(Convert.toLong(chatId), Convert.toLong(messageId))
                .onSuccess(fileIdOrPath -> {
                    if (fileIdOrPath == null) {
                        ctx.end();
                        return;
                    }
                    if (fileIdOrPath instanceof Integer fileId) {
                        ctx.json(JsonObject.of("fileId", fileId));
                        return;
                    }
                    String path = (String) fileIdOrPath;

                    ctx.response()
                            .putHeader("Content-Type", FileUtil.getMimeType(path))
                            .end(Buffer.buffer(FileUtil.readBytes(path)));
                })
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }

        JsonObject jsonObject = ctx.body().asJsonObject();
        Long chatId = jsonObject.getLong("chatId");
        Long messageId = jsonObject.getLong("messageId");
        Integer fileId = jsonObject.getInteger("fileId");
        if (chatId == null || messageId == null || fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.startDownload(chatId, messageId, fileId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownloadMultiple(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }

        JsonObject jsonObject = ctx.body().asJsonObject();
        Long chatId = jsonObject.getLong("chatId");
        JsonArray files = jsonObject.getJsonArray("files");
        if (chatId == null || CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }

        Future.any(files.stream()
                        .map(f -> {
                            JsonObject file = (JsonObject) f;
                            Long messageId = file.getLong("messageId");
                            Integer fileId = file.getInteger("fileId");
                            return telegramVerticle.startDownload(chatId, messageId, fileId);
                        })
                        .toList())
                .onSuccess(ctx::json)
                .onFailure(r -> {
                    log.error(r, "Failed to start download multiple files");
                    ctx.json(JsonObject.of("error", "Part of the files failed to start download"));
                    ctx.response().setStatusCode(400).end();
                });
    }

    private void handleFileCancelDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        if (fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.cancelDownload(fileId)
                .onSuccess(r -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileTogglePauseDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (fileId == null || isPaused == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.togglePauseDownload(fileId, isPaused)
                .onSuccess(r -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleAutoDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticle(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String chatId = ctx.request().getParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.toggleAutoDownload(Convert.toLong(chatId))
                .onSuccess(r -> ctx.end())
                .onFailure(ctx::fail);
    }

    public static Optional<TelegramVerticle> getTelegramVerticle(String telegramId) {
        Object id = NumberUtil.isNumber(telegramId) ? Convert.toLong(telegramId) : telegramId;
        return telegramVerticles.stream()
                .filter(t -> Objects.equals(t.getId(), id))
                .findFirst();
    }

    public static Optional<TelegramVerticle> getTelegramVerticle(long telegramId) {
        return telegramVerticles.stream()
                .filter(t -> t.telegramRecord != null && t.telegramRecord.id() == telegramId)
                .findFirst();
    }

    public static String getWSHandlerId(String sessionId) {
        return clients.get(sessionId);
    }

    private TelegramVerticle getTelegramVerticle(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        if (telegramVerticle == null) {
            ctx.response().setStatusCode(400)
                    .end(JsonObject.of("error", "Your session not link any telegram!").encode());
            return null;
        }
        return telegramVerticle;
    }
}
