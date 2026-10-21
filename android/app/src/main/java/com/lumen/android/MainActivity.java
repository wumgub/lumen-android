package com.lumen.android;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Register before super.onCreate so the bridge exposes window.MediaStore.
        registerPlugin(MediaStorePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
