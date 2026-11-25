package com.unsilence.app.ui.compose;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Runs in the test APK's own process, which has only platform dependencies. */
public final class DraftGrantFixtureActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if ((getCallingPackage() + ".test").equals(getPackageName())) {
            String name = UUID.fromString(getIntent().getStringExtra("fixture")).toString();
            File file = new File(getCacheDir(), name);
            Intent result = new Intent();
            if (getIntent().getBooleanExtra("remove", false)) {
                result.putExtra("removed", file.delete());
            } else {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    bitmap.recycle();
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
                result.setData(new Uri.Builder().scheme("content")
                    .authority(getPackageName() + ".draft-fixture").appendPath(name).build());
                result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            }
            setResult(RESULT_OK, result);
        }
        finish();
    }
}
