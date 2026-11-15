package org.telegram.messenger.amin.pyplugins;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Fan-out from {@code AminHooks} into the Python engine — M0.
 *
 * Every method is Throwable-isolated and cheap when the engine is
 * off (one prefs read): the upstream flows that call these must
 * never slow down for a disabled engine. Python dispatch itself
 * happens on the dedicated Python thread inside
 * {@link PyPluginsController}.
 */
public final class PyHookDispatcher {

    private PyHookDispatcher() {
    }

    /** Incoming messages (patch 0006, {@code onNewMessages}). */
    public static void onNewMessages(ArrayList<org.telegram.messenger.MessageObject> messages,
                                     int account) {
        try {
            if (messages == null || messages.isEmpty()) {
                return;
            }
            if (ApplicationLoader.applicationContext == null
                    || !PyPluginsController.isEngineEnabled(
                            ApplicationLoader.applicationContext)) {
                return;
            }
            for (org.telegram.messenger.MessageObject messageObject : messages) {
                if (messageObject != null && messageObject.messageOwner != null) {
                    PyPluginsController.dispatchMessage(account, messageObject.messageOwner);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Raw updates (patch 0006, {@code onRawUpdates}): M0 only
     *  forwards own "/" commands sent from another device to the
     *  same async command fan-out as local sends. */
    public static void onRawUpdates(ArrayList<TLRPC.Update> updates, int account) {
        try {
            if (updates == null || updates.isEmpty()) {
                return;
            }
            if (ApplicationLoader.applicationContext == null
                    || !PyPluginsController.isEngineEnabled(
                            ApplicationLoader.applicationContext)) {
                return;
            }
            for (TLRPC.Update update : updates) {
                try {
                    TLRPC.Message message = null;
                    if (update instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) {
                        message = ((org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) update).message;
                    } else if (update instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewChannelMessage) {
                        message = ((org.telegram.tgnet.tl.TL_update.TL_updateNewChannelMessage) update).message;
                    }
                    if (message != null && message.out && message.message != null
                            && message.message.startsWith("/")) {
                        PyPluginsController.dispatchOutgoingCommand(message.message);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Own outgoing "/" commands (patch 0013). Fire-and-forget in
     *  M0 — see {@link PyPluginsController#dispatchOutgoingCommand}. */
    public static void onOutgoingMessage(String text) {
        try {
            if (text == null || !text.startsWith("/")) {
                return;
            }
            if (ApplicationLoader.applicationContext == null
                    || !PyPluginsController.isEngineEnabled(
                            ApplicationLoader.applicationContext)) {
                return;
            }
            PyPluginsController.dispatchOutgoingCommand(text);
        } catch (Throwable ignored) {
        }
    }
}
