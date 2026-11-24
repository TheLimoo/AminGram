package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;

/**
 * Minimal NekoGram {@code MessageHelper} (Fix 3 / v3.0.3).
 *
 * Source: NekoGram (GPL-3.0), as vendored in exteraless
 * ({@code TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MessageHelper.java}).
 * Only the two methods the exteraless engine calls are reproduced here,
 * verbatim where possible: {@link #getPathToMessage} and
 * {@link #getMessagePlainText}. The rest of the 1486-line class (scheduling
 * dialogs, clipboard, translation helpers, ...) is not ported.
 *
 * One deliberate omission in {@link #getPathToMessage}: the vendored code
 * falls back to the NagramX/AyuGram deleted-message vault
 * ({@code NaConfig} + {@code AyuMessageUtils.findExistingFileByBaseNameFast})
 * when the file is not on disk. Neither class exists in AminGram, so that
 * branch is skipped: when the file is not found the method returns null,
 * exactly as it would with the vault disabled.
 */
public final class MessageHelper {

    private MessageHelper() {
    }

    public static String getPathToMessage(MessageObject messageObject) {
        return getPathToMessage(messageObject, UserConfig.selectedAccount);
    }

    public static String getPathToMessage(MessageObject messageObject, int accountId) {
        String path = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File f = new File(path);
            if (!f.exists()) {
                path = null;
            }
        }
        if (TextUtils.isEmpty(path)) {
            path = FileLoader.getInstance(accountId).getPathToMessage(messageObject.messageOwner).toString();
            File f = new File(path);
            if (!f.exists() || f.getAbsolutePath().endsWith("/cache")) {
                path = null;
            }
            // Fix 3: the NagramX deleted-message vault fallback
            // (NaConfig + AyuMessageUtils) is omitted — those classes
            // do not exist in AminGram. Without the vault the lookup
            // ends here with path == null.
        }
        if (TextUtils.isEmpty(path)) {
            File f = FileLoader.getInstance(accountId).getPathToAttach(messageObject.getDocument(), true);
            if (f.exists() && !f.getAbsolutePath().endsWith("/cache")) {
                path = f.getAbsolutePath();
            } else {
                f = FileLoader.getInstance(accountId).getPathToAttach(messageObject.getDocument());
                if (f.exists()) {
                    path = f.getAbsolutePath();
                }
            }
        }
        return path != null && !path.endsWith("/cache") ? path : null;
    }

    public static String getMessagePlainText(MessageObject messageObject, MessageObject.GroupedMessages messageGroup) {
        if (messageGroup != null) {
            MessageObject captionMessage = messageGroup.findCaptionMessageObject();
            if (captionMessage != null && !TextUtils.isEmpty(captionMessage.caption)) {
                return captionMessage.caption.toString();
            }
        }
        if (messageObject == null) {
            return null;
        }
        if (messageObject.isPoll()) {
            TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
            StringBuilder pollText = new StringBuilder(poll.question.text).append("\n");
            for (TLRPC.PollAnswer answer : poll.answers) {
                pollText.append("\n\uD83D\uDD18 ");
                pollText.append(answer.text.text);
            }
            return pollText.toString();
        } else if (!TextUtils.isEmpty(messageObject.getVoiceTranscription())) {
            return messageObject.messageOwner.voiceTranscription;
        }
        return messageObject.messageOwner.message;
    }
}
