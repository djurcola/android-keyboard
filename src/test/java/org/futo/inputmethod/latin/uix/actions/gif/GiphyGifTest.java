package org.futo.inputmethod.latin.uix.actions.gif;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GiphyGifTest {

    private static GiphyImage img(String url) {
        return new GiphyImage(url, "200", "200", "1000");
    }

    @Test
    public void thumbnailPrefersStillOverDownsampled() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(
                img("fw.gif"), img("still.gif"), img("down.gif"), img("dz.gif")));
        assertEquals("still.gif", gif.thumbnailUrl());
    }

    @Test
    public void thumbnailFallsBackToDownsampled() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(
                img("fw.gif"), null, img("down.gif"), null));
        assertEquals("down.gif", gif.thumbnailUrl());
    }

    @Test
    public void thumbnailFallsBackToFixedWidth() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(
                img("fw.gif"), null, null, null));
        assertEquals("fw.gif", gif.thumbnailUrl());
    }

    @Test
    public void thumbnailEmptyWhenNoImages() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(null, null, null, null));
        assertEquals("", gif.thumbnailUrl());
    }

    @Test
    public void insertionPrefersFixedWidth() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(
                img("fw.gif"), img("still.gif"), null, img("dz.gif")));
        assertEquals("fw.gif", gif.insertionUrl());
    }

    @Test
    public void insertionFallsBackToDownsized() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(
                null, img("still.gif"), null, img("dz.gif")));
        assertEquals("dz.gif", gif.insertionUrl());
    }

    @Test
    public void insertionFallsBackToStill() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(
                null, img("still.gif"), null, null));
        assertEquals("still.gif", gif.insertionUrl());
    }

    @Test
    public void insertionEmptyWhenNoImages() {
        GiphyGif gif = new GiphyGif("1", "t", new GiphyImages(null, null, null, null));
        assertEquals("", gif.insertionUrl());
    }
}
