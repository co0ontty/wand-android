package com.wand.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

final class NetworkMonitor {

    interface Listener {
        /**
         * 网络状态变化回调。注意：ConnectivityManager.NetworkCallback 的回调发生在
         * 系统 ConnectivityThread（非主线程），调用方需自行切换线程
         * （如 runOnUiThread）再做 UI / WebView 操作。
         */
        void onNetworkStateChanged(String state);
    }

    private final Context context;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile NetworkStateTracker<Network> stateTracker;
    private volatile boolean fallbackHasUsableNetwork = true;

    NetworkMonitor(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean hasUsableNetwork() {
        NetworkStateTracker<Network> tracker = stateTracker;
        return tracker == null ? fallbackHasUsableNetwork : tracker.hasUsableNetwork();
    }

    void register() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            Network active = cm.getActiveNetwork();
            NetworkCapabilities initialCaps = active == null
                    ? null : cm.getNetworkCapabilities(active);
            stateTracker = new NetworkStateTracker<>(
                    active,
                    initialCaps != null
                            && initialCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            );
            fallbackHasUsableNetwork = active != null;

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    emit(stateTracker.onAvailable(network));
                }

                @Override
                public void onLost(Network network) {
                    emit(stateTracker.onLost(network));
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    emit(stateTracker.onCapabilitiesChanged(
                            network,
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ));
                }

                private void emit(String state) {
                    if (state != null) listener.onNetworkStateChanged(state);
                }
            };
            // The default callback follows the network Android will actually route traffic over.
            // A generic INTERNET request also reports VPN/Wi-Fi candidates independently, which
            // made the old monitor emit misleading changes and miss the later validation event.
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    void unregister() {
        if (networkCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        networkCallback = null;
    }
}
