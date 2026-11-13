package com.construct.messenger.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/** Test APK only, standalone other UID. Java deliberately needs no target-APK Kotlin runtime. */
public class ShareSenderActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        handle();
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle();
    }
    private void handle() {
        String kind = getIntent().getStringExtra("kind");
        if (kind == null) kind = "photo";
        String[] kinds = kind.equals("mixed") ? new String[]{"photo", "video", "pdf"} : new String[]{kind};
        ArrayList<Uri> uris = new ArrayList<>();
        for (String part : kinds) {
            Uri.Builder builder = new Uri.Builder().scheme("content").authority("com.construct.messenger.test.share-source").appendPath(part);
            for (String key : new String[]{"bytes", "declared", "name"}) {
                String value = getIntent().getStringExtra(key);
                if (value != null) builder.appendQueryParameter(key, value);
            }
            uris.add(builder.build());
        }
        if (getIntent().getBooleanExtra("revoke", false)) {
            for (Uri uri : uris) revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            complete();
            return;
        }
        for (Uri uri : uris) grantUriPermission("com.construct.messenger", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!getIntent().getBooleanExtra("grantOnly", false)) {
            Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                    .setClassName("com.construct.messenger", "com.construct.messenger.MainActivity")
                    .setType(uris.size() == 1 ? (kind.equals("photo") ? "image/png" : kind.equals("video") ? "video/mp4" : kind.equals("pdf") ? "application/pdf" : "application/octet-stream") : "*/*")
                    .putExtra(Intent.EXTRA_TEXT, "SHARE-CANARY https://example.org/")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clip = ClipData.newRawUri("canary", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            send.setClipData(clip);
            startActivity(send);
        }
        complete();
    }
    private void complete() {
        String ack = getIntent().getStringExtra("ack");
        if (ack != null) grantUriPermission("com.construct.messenger", Uri.parse("content://com.construct.messenger.test.share-source/ack/" + ack), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        finish();
    }
}
