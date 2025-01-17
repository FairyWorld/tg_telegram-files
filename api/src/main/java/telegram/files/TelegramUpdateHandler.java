package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.function.Consumer;

public class TelegramUpdateHandler implements Client.ResultHandler {

    private static final Log log = LogFactory.get();

    private Consumer<TdApi.AuthorizationState> onAuthorizationStateUpdated;

    private Consumer<TdApi.UpdateFile> onFileUpdated;

    private Consumer<TdApi.UpdateFileDownloads> onFileDownloadsUpdated;

    private Consumer<TdApi.Object> onChatUpdated;

    private Consumer<TdApi.Message> onMessageReceived;

    @Override
    public void onResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                if (onAuthorizationStateUpdated != null)
                    onAuthorizationStateUpdated.accept(((TdApi.UpdateAuthorizationState) object).authorizationState);
                break;
            case TdApi.UpdateFile.CONSTRUCTOR:
                if (onFileUpdated != null)
                    onFileUpdated.accept((TdApi.UpdateFile) object);
            case TdApi.UpdateFileDownload.CONSTRUCTOR:
                log.trace("File download update: %s".formatted(object));
                break;
            case TdApi.UpdateFileDownloads.CONSTRUCTOR:
                if (onFileDownloadsUpdated != null)
                    onFileDownloadsUpdated.accept((TdApi.UpdateFileDownloads) object);
                break;
            case TdApi.UpdateNewMessage.CONSTRUCTOR:
                if (onMessageReceived != null) {
                    onMessageReceived.accept(((TdApi.UpdateNewMessage) object).message);
                }
            case TdApi.UpdateNewChat.CONSTRUCTOR:
            case TdApi.UpdateChatTitle.CONSTRUCTOR:
            case TdApi.UpdateChatPhoto.CONSTRUCTOR:
            case TdApi.UpdateChatReadInbox.CONSTRUCTOR:
            case TdApi.UpdateChatLastMessage.CONSTRUCTOR:
            case TdApi.UpdateChatPosition.CONSTRUCTOR:
                if (onChatUpdated != null) {
                    onChatUpdated.accept(object);
                }
            default:
                log.trace("Unsupported telegram update: %s".formatted(object));
        }
    }

    public void setOnAuthorizationStateUpdated(Consumer<TdApi.AuthorizationState> onAuthorizationStateUpdated) {
        this.onAuthorizationStateUpdated = onAuthorizationStateUpdated;
    }

    public void setOnFileUpdated(Consumer<TdApi.UpdateFile> onFileUpdated) {
        this.onFileUpdated = onFileUpdated;
    }

    public void setOnFileDownloadsUpdated(Consumer<TdApi.UpdateFileDownloads> onFileDownloadsUpdated) {
        this.onFileDownloadsUpdated = onFileDownloadsUpdated;
    }

    public void setOnChatUpdated(Consumer<TdApi.Object> onChatUpdated) {
        this.onChatUpdated = onChatUpdated;
    }

    public void setOnMessageReceived(Consumer<TdApi.Message> onMessageReceived) {
        this.onMessageReceived = onMessageReceived;
    }
}
