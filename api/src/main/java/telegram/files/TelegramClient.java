package telegram.files;

import cn.hutool.core.util.TypeUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;

public class TelegramClient {
    private static final Log log = LogFactory.get();

    private Client client;

    private boolean initialized = false;

    static {
        Client.setLogMessageHandler(0, new LogMessageHandler());

        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(Config.TELEGRAM_LOG_LEVEL));
            Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile(Path.of(Config.LOG_PATH, "tdlib.log").toString(),
                    1 << 27, false)));
        } catch (Client.ExecutionException error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }
    }

    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler) {
        synchronized (this) {
            if (!initialized) {
                client = Client.create(updateHandler, updateExceptionHandler, defaultExceptionHandler);
                initialized = true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method) {
        log.trace("Execute method: %s".formatted(TypeUtil.getTypeArgument(method.getClass())));
        if (!initialized) {
            throw new IllegalStateException("Client is not initialized");
        }
        return Future.future(promise -> client.send(method, object -> {
            if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
                promise.fail(new TelegramRunException((TdApi.Error) object));
            } else {
                promise.complete((R) object);
            }
        }));
    }

    public Client getNativeClient() {
        return client;
    }

    private static class LogMessageHandler implements Client.LogMessageHandler {
        @Override
        public void onLogMessage(int verbosityLevel, String message) {
            log.debug("TDLib: %s".formatted(message));
        }
    }
}
