/*
 * Copyright (C) 2010, 2011 Christopher Eby <kreed@kreed.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.kreed.vanilla;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

/**
 * Displays a flingable/draggable View of cover art/song info images
 * generated by CoverBitmap.
 */
public final class CoverView extends View implements Handler.Callback {
	private static final int STORE_SIZE = 3;
	private static int SNAP_VELOCITY = -1;

	/**
	 * The Handler with which to do background work. Will be null until
	 * setupHandler is called.
	 */
	private Handler mHandler;
	/**
	 * How to render cover art and metadata. One of
	 * CoverBitmap.STYLE_*
	 */
	private int mCoverStyle;

	/**
	 * The current set of songs: previous, current, and next.
	 */
	private Song[] mSongs = new Song[3];
	/**
	 * Cache of cover bitmaps generated for songs. The song ids are the keys.
	 */
	private Cache<Bitmap> mBitmapCache = new Cache<Bitmap>(8);

	private int mTimelinePos;
	private Scroller mScroller;
	private VelocityTracker mVelocityTracker;
	private float mLastMotionX;
	private float mStartX;
	private float mStartY;
	private int mTentativeCover = -1;
	/**
	 * Ignore the next pointer up event, for long presses.
	 */
	private boolean mIgnoreNextUp;

	/**
	 * Constructor intended to be called by inflating from XML.
	 */
	public CoverView(Context context, AttributeSet attributes)
	{
		super(context, attributes);

		mScroller = new Scroller(context);

		if (SNAP_VELOCITY == -1)
			SNAP_VELOCITY = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
	}

	/**
	 * Setup the Handler to act on the given looper. This must be called before
	 * the CoverView is used.
	 *
	 * @param looper The Looper to use.
	 */
	public void setupHandler(Looper looper)
	{
		mHandler = new Handler(looper, this);
	}

	/**
	 * Set the cover style. Must be one of CoverBitmap.STYLE_. Will only apply
	 * to bitmaps rendered after this method is called.
	 */
	public void setCoverStyle(int style)
	{
		mCoverStyle = style;
	}

	/**
	 * Query the service for initial song info.
	 */
	public void initialize()
	{
		mTimelinePos = ContextApplication.getService().getTimelinePos();
		querySongs(true);
	}

	/**
	 * Move to the next or previous song.
	 *
	 * @param delta -1 or 1, indicate the previous or next song, respectively
	 */
	public void go(int delta)
	{
		int i = delta > 0 ? STORE_SIZE - 1 : 0;
		int from = delta > 0 ? 1 : 0;
		int to = delta > 0 ? 0 : 1;
		System.arraycopy(mSongs, from, mSongs, to, STORE_SIZE - 1);
		mSongs[i] = null;

		mTimelinePos += delta;
		resetScroll();
		invalidate();
	}

	/**
	 * Reset the scroll position to its default state.
	 */
	private void resetScroll()
	{
		if (!mScroller.isFinished())
			mScroller.abortAnimation();
		scrollTo(getWidth(), 0);
	}

	/**
	 * Recreate all the necessary cached bitmaps.
	 */
	private void regenerateBitmaps()
	{
		Object[] bitmaps = mBitmapCache.clear();
		for (int i = bitmaps.length; --i != -1; ) {
			if (bitmaps[i] != null)
				((Bitmap)bitmaps[i]).recycle();
			bitmaps[i] = null;
		}
		for (int i = STORE_SIZE; --i != -1; )
			setSong(i, mSongs[i]);
	}

	/**
	 * Recreate the cover art views and reset the scroll position whenever the
	 * size of this view changes.
	 */
	@Override
	protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight)
	{
		if (width == 0 || height == 0)
			return;

		regenerateBitmaps();
		resetScroll();
	}

	/**
	 * Paint the cover art views to the canvas.
	 */
	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);

		Rect clip = canvas.getClipBounds();
		Paint paint = new Paint();
		int width = getWidth();
		int height = getHeight();

		canvas.drawColor(Color.BLACK);

		for (int x = 0, i = 0; i != STORE_SIZE; ++i, x += width) {
			if (mSongs[i] != null && clip.intersects(x, 0, x + width, height)) {
				long id = mSongs[i].id;
				if (id == -1)
					continue;
				Bitmap bitmap = mBitmapCache.get(id);
				if (bitmap != null) {
					int xOffset = (width - bitmap.getWidth()) / 2;
					int yOffset = (height - bitmap.getHeight()) / 2;
					canvas.drawBitmap(bitmap, x + xOffset, yOffset, paint);
				}
			}
		}
	}

	/**
	 * Scrolls the view when dragged. Animates a fling to one of the three covers
	 * when finished. The cover flung to will be either the nearest cover, or if
	 * the fling is fast enough, the cover in the direction of the fling.
	 *
	 * Also performs a click on the view when it is tapped without dragging.
	 */
	@Override
	public boolean onTouchEvent(MotionEvent ev)
	{
		if (mVelocityTracker == null)
			mVelocityTracker = VelocityTracker.obtain();
 		mVelocityTracker.addMovement(ev);

		mHandler.removeMessages(MSG_LONG_CLICK);

 		float x = ev.getX();
 		int scrollX = getScrollX();
 		int width = getWidth();
 
 		switch (ev.getAction()) {
 		case MotionEvent.ACTION_DOWN:
 			if (!mScroller.isFinished())
				mScroller.abortAnimation();

 			mStartX = x;
 			mStartY = ev.getY();
			mLastMotionX = x;

			mHandler.sendEmptyMessageDelayed(MSG_LONG_CLICK, ViewConfiguration.getLongPressTimeout());
			break;
		case MotionEvent.ACTION_MOVE:
			int deltaX = (int) (mLastMotionX - x);
			mLastMotionX = x;

			if (deltaX < 0) {
				int availableToScroll = scrollX - (mTimelinePos == 0 ? width : 0);
				if (availableToScroll > 0)
					scrollBy(Math.max(-availableToScroll, deltaX), 0);
			} else if (deltaX > 0) {
				int availableToScroll = getWidth() * 2 - scrollX;
				if (availableToScroll > 0)
					scrollBy(Math.min(availableToScroll, deltaX), 0);
			}
			break;
		case MotionEvent.ACTION_UP:
			if (Math.abs(mStartX - x) + Math.abs(mStartY - ev.getY()) < 10) {
				// A long press was performed and thus the normal action should
				// not be executed.
				if (mIgnoreNextUp)
					mIgnoreNextUp = false;
				else
					performClick();
			} else {
				VelocityTracker velocityTracker = mVelocityTracker;
				velocityTracker.computeCurrentVelocity(250);
				int velocity = (int) velocityTracker.getXVelocity();

				int min = mTimelinePos == 0 ? 1 : 0;
				int max = 2;
				int nearestCover = (scrollX + width / 2) / width;
				int whichCover = Math.max(min, Math.min(nearestCover, max));

				if (velocity > SNAP_VELOCITY && whichCover != min)
					--whichCover;
				else if (velocity < -SNAP_VELOCITY && whichCover != max)
					++whichCover;

				int newX = whichCover * width;
				int delta = newX - scrollX;
				mScroller.startScroll(scrollX, 0, delta, 0, Math.abs(delta) * 2);
				if (whichCover != 1)
					mTentativeCover = whichCover;

				postInvalidate();
			}

			if (mVelocityTracker != null) {
				mVelocityTracker.recycle();
				mVelocityTracker = null;
			}

			break;
 		}
		return true;
	}

	/**
	 * Update position for fling scroll animation and, when it is finished,
	 * notify PlaybackService that the user has requested a track change and
	 * update the cover art views.
	 */
	@Override
	public void computeScroll()
	{
		if (mScroller.computeScrollOffset()) {
			scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
			postInvalidate();
		} else if (mTentativeCover != -1) {
			int delta = mTentativeCover - 1;
			mTentativeCover = -1;
			mHandler.sendMessage(mHandler.obtainMessage(PlaybackActivity.MSG_SET_SONG, delta, 0));
			go(delta);
		}
	}

	/**
	 * Generates a bitmap for the given Song and stores it in the cache.
	 *
	 * Prunes old bitmaps if the timeline becomes full.
	 */
	private void generateBitmap(Song song)
	{
		if (song == null || song.id == -1)
			return;

		Bitmap bitmap = mBitmapCache.get(song.id);
		if (bitmap == null) {
			mBitmapCache.put(song.id, CoverBitmap.createBitmap(mCoverStyle, song, getWidth(), getHeight(), mBitmapCache.discardOldest()));
			postInvalidate();
		} else {
			mBitmapCache.touch(song.id);
		}
	}

	/**
	 * Set the Song at position <code>i</code> to <code>song</code>, generating
	 * the bitmap for it in the background if needed.
	 */
	private void setSong(int i, final Song song)
	{
		mSongs[i] = song;
		if (song != null)
			mHandler.sendMessage(mHandler.obtainMessage(MSG_GENERATE_BITMAP, song));
	}

	/**
	 * Query current Song for all positions with null songs.
	 *
	 * @param force Query all songs, even those that are non-null
	 */
	private void querySongs(boolean force)
	{
		PlaybackService service = ContextApplication.getService();
		for (int i = STORE_SIZE; --i != -1; ) {
			if (force || mSongs[i] == null)
				setSong(i, service.getSong(i - STORE_SIZE / 2));
		}
	}

	/**
	 * Handle an intent broadcasted by the PlaybackService. This must be called
	 * to react to song changes in PlaybackService.
	 *
	 * @param intent The intent that was broadcast
	 */
	public void receive(Intent intent)
	{
		String action = intent.getAction();
		if (PlaybackService.EVENT_REPLACE_SONG.equals(action)) {
			int i = STORE_SIZE / 2 + intent.getIntExtra("pos", 0);
			setSong(i, (Song)intent.getParcelableExtra("song"));
		} else if (PlaybackService.EVENT_CHANGED.equals(action)) {
			mTimelinePos = intent.getIntExtra("pos", 0);
			Song currentSong = mSongs[STORE_SIZE / 2];
			Song playingSong = intent.getParcelableExtra("song");
			boolean force = currentSong == null || !currentSong.equals(playingSong);
			querySongs(force);
		}
	}

	/**
	 * Call {@link CoverView#generateBitmap(Song)} for the given song.
	 *
	 * obj must be the Song to generate a bitmap for.
	 */
	private static final int MSG_GENERATE_BITMAP = 0;
	/**
	 * Tell PlaybackService to change the current song.
	 * 
	 * arg1 should be the delta, -1 or 1, indicating the previous or next song,
	 * respectively.
	 */
	static final int MSG_SET_SONG = 1;
	/**
	 * Perform a long click.
	 *
	 * @see View#performLongClick()
	 */
	private static final int MSG_LONG_CLICK = 2;

	public boolean handleMessage(Message message)
	{
		switch (message.what) {
		case MSG_GENERATE_BITMAP:
			generateBitmap((Song)message.obj);
			break;
		case MSG_SET_SONG:
			if (message.arg1 == 1)
				ContextApplication.getService().nextSong();
			else
				ContextApplication.getService().previousSong();
			break;
		case MSG_LONG_CLICK:
			mIgnoreNextUp = true;
			performLongClick();
			break;
		default:
			return false;
		}

		return true;
	}
}
