package com.otaliastudios.transcoder.source;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.IOException;

import androidx.annotation.NonNull;

/**
 * A {@link DataSource} backed by an Uri, possibly
 * a content:// uri.
 */
public class UriDataSource extends DefaultDataSource {

    @NonNull private final Context context;
    @NonNull private final Uri uri;

    public UriDataSource(@NonNull Context context, @NonNull Uri uri) {
        this.context = context.getApplicationContext();
        this.uri = uri;
    }

    @Override
    protected void initializeExtractor(@NonNull MediaExtractor extractor) throws IOException {
        if(!checkIfSourceExists(uri))
            return;
        extractor.setDataSource(context, uri, null);
    }

    @Override
    protected void initializeRetriever(@NonNull MediaMetadataRetriever retriever) {
        if(!checkIfSourceExists(uri))
            return;
        retriever.setDataSource(context, uri);
    }

    @Override
    public String mediaId() {
        return uri.toString();
    }
    private boolean checkIfSourceExists(Uri uri) {
        try {
            context.getContentResolver().openInputStream(uri).close();
            return true;
        } catch (FileNotFoundException exception) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
