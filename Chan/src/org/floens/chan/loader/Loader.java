package org.floens.chan.loader;

import java.util.ArrayList;
import java.util.List;

import org.floens.chan.ChanApplication;
import org.floens.chan.model.Loadable;
import org.floens.chan.model.Post;
import org.floens.chan.utils.Logger;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.android.volley.Response;
import com.android.volley.ServerError;
import com.android.volley.VolleyError;

public class Loader {
    private static final String TAG = "Loader";
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static final int[] watchTimeouts = {5, 10, 15, 20, 30, 60, 90, 120, 180, 240, 300};

    private final List<LoaderListener> listeners = new ArrayList<LoaderListener>();
    private final Loadable loadable;
    private final SparseArray<Post> postsById = new SparseArray<Post>();

    private boolean destroyed = false;
    private ChanReaderRequest request;

    private int currentTimeout;
    private int lastPostCount;
    private long lastLoadTime;
    private Runnable pendingRunnable;

    public Loader(Loadable loadable) {
        this.loadable = loadable;
    }

    /**
     * Add a LoaderListener
     * @param l the listener to add
     */
    public void addListener(LoaderListener l) {
        listeners.add(l);
    }

    /**
     * Remove a LoaderListener
     * @param l the listener to remove
     * @return true if there are no more listeners, false otherwise
     */
    public boolean removeListener(LoaderListener l) {
        listeners.remove(l);
        if (listeners.size() == 0) {
            clearTimer();
            destroyed = true;
            return true;
        } else {
            return false;
        }
    }

    public void requestData() {
        if (request != null) {
            request.cancel();
        }

        if (loadable.isBoardMode()) {
            loadable.no = 0;
            loadable.listViewIndex = 0;
            loadable.listViewTop = 0;
        }

        currentTimeout = 0;

        request = getData(loadable);
    }

    public void requestNextData() {
        if (loadable.isBoardMode()) {
            loadable.no++;

            if (request != null) {
                request.cancel();
            }

            request = getData(loadable);
        } else if (loadable.isThreadMode()) {
            if (request != null) {
                return;
            }

            clearTimer();

            request = getData(loadable);
        }
    }

    public void requestNextDataResetTimer() {
        currentTimeout = 0;
        requestNextData();
    }

    /**
     * @return Returns if this loader is currently loading
     */
    public boolean isLoading() {
        return request != null;
    }

    public Post findPostById(int id) {
        return postsById.get(id);
    }

    public Loadable getLoadable() {
        return loadable;
    }

    public void onStart() {
        if (loadable.isThreadMode()) {
            requestNextDataResetTimer();
        }
    }

    public void onStop() {
        clearTimer();
    }

    public long getTimeUntilReload() {
        if (request != null) {
            return 0L;
        } else {
            long waitTime = watchTimeouts[currentTimeout] * 1000L;
            return lastLoadTime + waitTime - System.currentTimeMillis();
        }
    }

    private void setTimer(int postCount) {
        if (pendingRunnable != null) {
            clearTimer();
        }

        if (pendingRunnable == null) {
            pendingRunnable = new Runnable() {
                @Override
                public void run() {
                    pendingRunnableCallback();
                };
            };

            lastPostCount = postCount;

            if (postCount > lastPostCount) {
                currentTimeout = 0;
            } else {
                currentTimeout = Math.min(watchTimeouts.length - 1, currentTimeout + 1);
            }

            handler.postDelayed(pendingRunnable, watchTimeouts[currentTimeout] * 1000L);
        }
    }

    private void clearTimer() {
        if (pendingRunnable != null) {
            handler.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
    }

    private void pendingRunnableCallback() {
        Logger.d(TAG, "pending callback");
        pendingRunnable = null;
        requestNextData();
    }

    private ChanReaderRequest getData(Loadable loadable) {
        Logger.i(TAG, "Requested " + loadable.board + ", " + loadable.no);

        ChanReaderRequest request = ChanReaderRequest.newInstance(loadable, new Response.Listener<List<Post>>() {
            @Override
            public void onResponse(List<Post> list) {
                Loader.this.request = null;
                onData(list);
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Loader.this.request = null;
                onError(error);
            }
        });

        ChanApplication.getVolleyRequestQueue().add(request);

        return request;
    }

    private void onData(List<Post> result) {
        if (destroyed) return;

        Logger.test("ondata in loader");

        postsById.clear();
        for (Post post : result) {
            postsById.append(post.no, post);
        }

        for (LoaderListener l : listeners) {
            l.onData(result, loadable.isBoardMode());
        }

        lastLoadTime = System.currentTimeMillis();
        if (loadable.isThreadMode()) {
            setTimer(result.size());
        }
    }

    private void onError(VolleyError error) {
        if (destroyed) return;

        Logger.e(TAG, "Error loading " + error.getMessage(), error);

        // 404 with more pages already loaded means endofline
        if ((error instanceof ServerError) && loadable.isBoardMode() && loadable.no > 0) {
            error = new EndOfLineException();
        }

        for (LoaderListener l : listeners) {
            l.onError(error);
        }

        clearTimer();
    }

    public static interface LoaderListener {
        public void onData(List<Post> result, boolean append);
        public void onError(VolleyError error);
    }
}




