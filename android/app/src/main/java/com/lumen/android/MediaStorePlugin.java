package com.lumen.android;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;

/**
 * Exposes the device's music library (Android's MediaStore) to the WebView so
 * Lumen can browse songs in place instead of importing copies through the file
 * picker. It only ever reads metadata and hands back content:// URIs; the bytes
 * are streamed later by Capacitor's own local server when a track is played.
 *
 * The one permission we need shifted name in Android 13: READ_MEDIA_AUDIO there,
 * READ_EXTERNAL_STORAGE on 12 and below. Both are declared as aliases and we ask
 * for whichever one the running OS actually uses.
 */
@CapacitorPlugin(
    name = "MediaStore",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "audioLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MediaStorePlugin extends Plugin {

    private String neededAlias() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "audio" : "audioLegacy";
    }

    private boolean hasAudioPermission() {
        return getPermissionState(neededAlias()) == PermissionState.GRANTED;
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasAudioPermission());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (hasAudioPermission()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias(neededAlias(), call, "permissionCallback");
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasAudioPermission());
        call.resolve(ret);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (!hasAudioPermission()) {
            call.reject("permission-denied");
            return;
        }
        try {
            JSObject ret = new JSObject();
            ret.put("tracks", queryAudio());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("scan-failed: " + e.getMessage(), e);
        }
    }

    private JSArray queryAudio() {
        JSArray out = new JSArray();
        ContentResolver resolver = getContext().getContentResolver();

        // ALBUM_ARTIST only exists as a column from API 30; older OSes throw if
        // it's in the projection, so only ask for it when it's really there.
        boolean hasAlbumArtist = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

        ArrayList<String> proj = new ArrayList<>();
        proj.add(MediaStore.Audio.Media._ID);
        proj.add(MediaStore.Audio.Media.TITLE);
        proj.add(MediaStore.Audio.Media.ARTIST);
        proj.add(MediaStore.Audio.Media.ALBUM);
        proj.add(MediaStore.Audio.Media.ALBUM_ID);
        proj.add(MediaStore.Audio.Media.DURATION);
        proj.add(MediaStore.Audio.Media.TRACK);
        proj.add(MediaStore.Audio.Media.YEAR);
        proj.add(MediaStore.Audio.Media.DATE_ADDED);
        if (hasAlbumArtist) proj.add(MediaStore.Audio.Media.ALBUM_ARTIST);

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sort = MediaStore.Audio.Media.ARTIST + " COLLATE NOCASE ASC, "
            + MediaStore.Audio.Media.ALBUM + " COLLATE NOCASE ASC, "
            + MediaStore.Audio.Media.TRACK + " ASC";

        // Legacy per-album art provider; served (when present) via convertFileSrc.
        Uri albumArtBase = Uri.parse("content://media/external/audio/albumart");

        try (Cursor c = resolver.query(collection, proj.toArray(new String[0]), selection, null, sort)) {
            if (c == null) return out;

            int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
            int iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR);
            int iDateAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int iAlbumArtist = hasAlbumArtist ? c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) : -1;

            while (c.moveToNext()) {
                long id = c.getLong(iId);
                long albumId = c.getLong(iAlbumId);

                // MediaStore's TRACK often encodes disc*1000 + track.
                int track = c.isNull(iTrack) ? 0 : c.getInt(iTrack);
                int trackNo = track % 1000;
                int discNo = track >= 1000 ? track / 1000 : 0;

                String artist = c.getString(iArtist);
                // MediaStore uses the literal "<unknown>" for tagless files.
                if (artist == null || artist.equals("<unknown>")) artist = "";
                String albumArtist = iAlbumArtist >= 0 ? c.getString(iAlbumArtist) : null;

                String title = c.getString(iTitle);
                String album = c.getString(iAlbum);
                long durMs = c.isNull(iDur) ? 0 : c.getLong(iDur);
                long dateAddedSec = c.isNull(iDateAdded) ? 0 : c.getLong(iDateAdded);

                String uri = ContentUris.withAppendedId(collection, id).toString();
                String artUri = albumId > 0
                    ? ContentUris.withAppendedId(albumArtBase, albumId).toString()
                    : "";

                JSObject t = new JSObject();
                t.put("id", id);
                t.put("title", title != null ? title : "");
                t.put("artist", artist);
                t.put("albumArtist", albumArtist != null && !albumArtist.isEmpty() ? albumArtist : artist);
                t.put("album", album != null ? album : "");
                t.put("albumId", albumId);
                t.put("genre", "");
                t.put("year", c.isNull(iYear) ? 0 : c.getInt(iYear));
                t.put("trackNo", trackNo);
                t.put("discNo", discNo);
                t.put("duration", durMs / 1000.0);
                t.put("addedAt", dateAddedSec * 1000L);
                t.put("uri", uri);
                t.put("artUri", artUri);
                t.put("hasArt", albumId > 0 ? 1 : 0);
                t.put("isVideo", 0);
                out.put(t);
            }
        }
        return out;
    }
}
