package org.lyaaz.fuckgboard;

import android.os.Bundle;

/** Only structured dictionary operations; no command or arbitrary-path endpoint. */
interface IDictionaryUserService {
    void destroy() = 16777114;

    Bundle execute(String action, String dataDir, int uid, String language,
        @nullable String name, @nullable String source, boolean hasModifiedMillis,
        long modifiedMillis) = 1;
}
