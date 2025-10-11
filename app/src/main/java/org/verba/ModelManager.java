package org.verba;

import android.content.Context;

import org.vosk.Model;
import org.vosk.android.StorageService;

import java.util.function.Consumer;

public class ModelManager {
    private static Model model;
    private static boolean isLoaded = false;
    private static final Object lock = new Object();

    public static void loadModel(Context context, Runnable onSuccess, Consumer<Exception> onError) {
        if (isLoaded && model != null) {
            onSuccess.run();
            return;
        }

        synchronized (lock) {
            if (isLoaded && model != null) {
                onSuccess.run();
                return;
            }

            StorageService.unpack(context, "model-en-us", "model",
                    (loadedModel) -> {
                        model = loadedModel;
                        isLoaded = true;
                        onSuccess.run();
                    },
                    onError::accept
            );
        }
    }

    public static Model getModel() {
        return model;
    }

    public static void release() {
        synchronized (lock) {
            if (model != null) {
                model = null;
                isLoaded = false;
            }
        }
    }
}