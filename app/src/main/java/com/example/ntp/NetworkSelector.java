package com.example.ntp;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the network a request should leave on, instead of trusting Android's default network.
 *
 * The MicroTouch IDC tablet has an internal "usb0" link (192.168.63.0/24, no gateway, no DNS) that
 * Android also treats as Ethernet. On an isolated Testrun network, where nothing validates, that
 * link can stay the default network, and traffic for 10.10.10.5 would go nowhere. Choosing by
 * route sends it out eth0, which is on Testrun's 10.10.10.0/24.
 * Tag: NTPValidation
 */
final class NetworkSelector {
    private NetworkSelector() {}

    /**
     * For an IP literal: the network with a route to it, preferring a specific (on-link) route over
     * a default route. For a hostname: a network with a default route and DNS servers.
     * Ties go to validated, then Ethernet, then Wi-Fi. Returns null if nothing can reach it.
     */
    @SuppressWarnings("deprecation") // getAllNetworks(): still the simplest snapshot on API 35
    static Network forHost(ConnectivityManager cm, String host) {
        InetAddress literal = literalAddress(host);
        Network best = null;
        int bestScore = -1;
        for (Network network : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(network);
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (lp == null || nc == null || !usable(nc)) continue;

            int routeScore = -1;
            for (RouteInfo route : lp.getRoutes()) {
                if (literal != null) {
                    if (route.matches(literal)) {
                        routeScore = Math.max(routeScore, route.isDefaultRoute() ? 1 : 2);
                    }
                } else if (route.isDefaultRoute() && !lp.getDnsServers().isEmpty()) {
                    routeScore = Math.max(routeScore, 1);
                }
            }
            if (routeScore < 0) continue;

            int score = routeScore * 10 + preference(nc);
            if (score > bestScore) {
                bestScore = score;
                best = network;
            }
        }
        return best;
    }

    static Network forUrl(ConnectivityManager cm, String url) {
        try {
            return forHost(cm, new URL(url).getHost());
        } catch (Exception e) {
            return null;
        }
    }

    static boolean usable(NetworkCapabilities nc) {
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    static boolean isValidated(NetworkCapabilities nc) {
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** e.g. "eth0 10.10.10.14/24 Ethernet" */
    static String describe(ConnectivityManager cm, Network network) {
        if (network == null) return "none";
        LinkProperties lp = cm.getLinkProperties(network);
        NetworkCapabilities nc = cm.getNetworkCapabilities(network);
        StringBuilder sb = new StringBuilder(lp != null && lp.getInterfaceName() != null ? lp.getInterfaceName() : network.toString());
        if (lp != null) {
            for (LinkAddress address : lp.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) sb.append(' ').append(address);
            }
        }
        if (nc != null) {
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) sb.append(" Ethernet");
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append(" Wi-Fi");
            if (isValidated(nc)) sb.append(" (internet)");
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    static String describeAll(ConnectivityManager cm) {
        List<String> parts = new ArrayList<>();
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc != null && usable(nc)) parts.add(describe(cm, network));
        }
        return parts.isEmpty() ? "no networks" : String.join("; ", parts);
    }

    /** Parses an IPv4/IPv6 literal without any DNS lookup; null for hostnames. */
    static InetAddress literalAddress(String host) {
        if (host == null) return null;
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":")) {
            try {
                return InetAddress.getByName(host); // literal: no DNS query is made
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static int preference(NetworkCapabilities nc) {
        int score = 0;
        if (isValidated(nc)) score += 4;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 2;
        else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 1;
        return score;
    }
}
