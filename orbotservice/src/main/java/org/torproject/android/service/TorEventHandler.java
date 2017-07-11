package org.torproject.android.service;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.torproject.android.control.EventHandler;
import org.torproject.android.service.util.Prefs;

/**
 * Created by n8fr8 on 9/25/16.
 */
public class TorEventHandler implements EventHandler, TorServiceConstants {

    private TorService mService;


    private long lastRead = -1;
    private long lastWritten = -1;
    private long mTotalTrafficWritten = 0;
    private long mTotalTrafficRead = 0;

    private NumberFormat mNumberFormat = null;


    private HashMap<String,Node> hmBuiltNodes = new HashMap<>();
    private HashMap<String, Circuit> hmBuiltCircuits = new HashMap<>();

    public class Circuit {
        String status;
        String id;
        String path;
        String purpose;
    }
    public class Node
    {
        String status;
        String id;
        String name;
        String ipAddress;
        String country;
        String organization;
    }

    public HashMap<String,Node> getNodes ()
    {
        return hmBuiltNodes;
    }

    public HashMap<String, Circuit> getCircuits() {return hmBuiltCircuits;}

    public TorEventHandler (TorService service)
    {
        mService = service;
        mNumberFormat = NumberFormat.getInstance(Locale.getDefault()); //localized numbers!

    }

    @Override
    public void message(String severity, String msg) {
        mService.logNotice(severity + ": " + msg);
    }

    @Override
    public void newDescriptors(List<String> orList) {
    }

    @Override
    public void orConnStatus(String status, String orName) {

        StringBuilder sb = new StringBuilder();
        sb.append("orConnStatus (");
        sb.append(parseNodeName(orName) );
        sb.append("): ");
        sb.append(status);

        mService.debug(sb.toString());
    }

    @Override
    public void streamStatus(String status, String streamID, String target) {

        StringBuilder sb = new StringBuilder();
        sb.append("StreamStatus (");
        sb.append((streamID));
        sb.append("): ");
        sb.append(status);

        mService.logNotice(sb.toString());
    }

    @Override
    public void unrecognized(String type, String msg) {

        StringBuilder sb = new StringBuilder();
        sb.append("Message (");
        sb.append(type);
        sb.append("): ");
        sb.append(msg);

        mService.logNotice(sb.toString());
    }

    @Override
    public void bandwidthUsed(long read, long written) {

        if (read != lastRead || written != lastWritten)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(formatCount(read));
            sb.append(" \u2193");
            sb.append(" / ");
            sb.append(formatCount(written));
            sb.append(" \u2191");

            int iconId = R.drawable.ic_stat_tor;

            if (read > 0 || written > 0)
                iconId = R.drawable.ic_stat_tor_xfer;

            if (mService.hasConnectivity() && Prefs.expandedNotifications())
                mService.showToolbarNotification(sb.toString(), mService.getNotifyId(), iconId);

            mTotalTrafficWritten += written;
            mTotalTrafficRead += read;
        }

        lastWritten = written;
        lastRead = read;

        mService.sendCallbackBandwidth(lastWritten, lastRead, mTotalTrafficWritten, mTotalTrafficRead);
    }

    @Override
    public void workStatus(boolean status) {
        mService.debug("workStatus: " + status);
        if (status) {
            mService.holdWakeLock();
        } else {
            mService.releaseWakeLock();
        }
    }

    private String formatCount(long count) {
        // Converts the supplied argument into a string.

        // Under 2Mb, returns "xxx.xKb"
        // Over 2Mb, returns "xxx.xxMb"
        if (mNumberFormat != null)
            if (count < 1e6)
                return mNumberFormat.format(Math.round((float)((int)(count*10/1024))/10)) + "kbps";
            else
                return mNumberFormat.format(Math.round((float)((int)(count*100/1024/1024))/100)) + "mbps";
        else
            return "";

        //return count+" kB";
    }

    @Override
    public void circuitStatus(String status, String circID, String path, String purpose) {

        /* once the first circuit is complete, then announce that Orbot is on*/
        if (mService.getCurrentStatus() == STATUS_STARTING && TextUtils.equals(status, "BUILT"))
            mService.sendCallbackStatus(STATUS_ON);

        StringBuilder sb = new StringBuilder();
        sb.append("Circuit (");
        sb.append((circID));
        sb.append(") ");
        sb.append("[");
        sb.append(purpose);
        sb.append("] ");
        sb.append(status);
        sb.append(": ");

        StringTokenizer st = new StringTokenizer(path,",");
        Node node = null;

        while (st.hasMoreTokens())
        {
            String nodePath = st.nextToken();
            node = new Node();

            String[] nodeParts;

            if (nodePath.contains("="))
                nodeParts = nodePath.split("=");
            else
                nodeParts = nodePath.split("~");

            if (nodeParts.length == 1)
            {
                node.id = nodeParts[0].substring(1);
                node.name = node.id;
            }
            else if (nodeParts.length == 2)
            {
                node.id = nodeParts[0].substring(1);
                node.name = nodeParts[1];
             }

            node.status = status;

            sb.append(node.name);

            if (st.hasMoreTokens())
                sb.append (" > ");
        }

        if (Prefs.useDebugLogging())
            mService.debug(sb.toString());
        else if(status.equals("BUILT"))
            mService.logNotice(sb.toString());
        else if (status.equals("CLOSED"))
            mService.logNotice(sb.toString());

        if (Prefs.expandedNotifications())
        {
            //get IP from last nodename
            if(status.equals("BUILT")){

                if (node.ipAddress == null)
                    mService.exec(new ExternalIPFetcher(node));

                hmBuiltNodes.put(circID, node);
            }

            if (status.equals("CLOSED"))
            {
                hmBuiltNodes.remove(circID);

            }
        }

        // If HS is turned on, ensure that we are maintaining a introduction circuit at all times
        // by holding wake lock when we have no such circuits and releasing it when we've built one
        if (mService.isHasHiddenServices()) {
            Circuit circ = new Circuit();
            circ.id = circID;
            circ.status = status;
            circ.path = path;
            circ.purpose = purpose;

            if (status.equals("BUILT")) {
                hmBuiltCircuits.put(circID, circ);
            } else if (status.equals("CLOSED")) {
                hmBuiltCircuits.remove(circID);
            }

            mService.checkIfWakelockRequired();
        }
    }

    @Override
    public void circuitMinorStatus(String event, String circID, String purpose) {
        if (!mService.isHasHiddenServices())
            return;

        if (hmBuiltCircuits.containsKey(circID))
            hmBuiltCircuits.get(circID).purpose = purpose;

        mService.checkIfWakelockRequired();
    }

    public boolean hasBuiltHiddenServiceCircuits() {
        for (Circuit circuit : hmBuiltCircuits.values()) {
            if (circuit.purpose.equals("HS_SERVICE_INTRO"))
                return true;
        }
        return false;
    }

    public void initCircuitStatus(String raw) {
        hmBuiltCircuits.clear();
        String[] list = raw.split("\r\n");
        for (String event : list) {
            if (event.trim().isEmpty())
                continue;
            String[] parts = event.split(" ");
            Circuit circuit = new Circuit();
            circuit.id = parts[0];
            circuit.status = parts[1];
            Map<String, String> keywordAttr = getKeywordedArgs(event);
            circuit.purpose = keywordAttr.containsKey("PURPOSE") ? keywordAttr.get("PURPOSE") : "";
            // we only care about potentially missing BUILT circuits
            if (!circuit.status.equals("BUILT"))
                continue;
            hmBuiltCircuits.put(circuit.id, circuit);
        }
    }

    private Map<String, String> getKeywordedArgs(String content) {
        Pattern quotedKwArg = Pattern.compile("^(.*) ([A-Za-z0-9_]+)=\"(.*)\"$");
        Pattern kwArg = Pattern.compile("^(.*) ([A-Za-z0-9_]+)=(\\S*)$");
        Map<String, String> keywordArgs = new HashMap<>();
        while (true) {
            // First try to match quoted args
            Matcher m = quotedKwArg.matcher(content);
            if (!m.find())
                // If quoted args fail, try without quotes
                m = kwArg.matcher(content);

            if (m.find()) {
                content = m.group(1);
                keywordArgs.put(m.group(2), m.group(3));
            } else {
                break;
            }
        }
        return keywordArgs;
    }

    private class ExternalIPFetcher implements Runnable {

        private Node mNode;
        private int MAX_ATTEMPTS = 3;
        private final static String ONIONOO_BASE_URL = "https://onionoo.torproject.org/details?fields=country_name,as_name,or_addresses&lookup=";

        public ExternalIPFetcher (Node node)
        {
            mNode = node;
        }

        public void run ()
        {

            for (int i = 0; i < MAX_ATTEMPTS; i++)
            {
                if (mService.getControlConnection() != null)
                {
                    try {

                        URLConnection conn = null;

                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8118));
                        conn = new URL(ONIONOO_BASE_URL + mNode.id).openConnection(proxy);

                        conn.setRequestProperty("Connection","Close");
                        conn.setConnectTimeout(60000);
                        conn.setReadTimeout(60000);

                        InputStream is = conn.getInputStream();

                        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                        // getting JSON string from URL

                        StringBuffer json = new StringBuffer();
                        String line = null;

                        while ((line = reader.readLine())!=null)
                            json.append(line);

                        JSONObject jsonNodeInfo = new org.json.JSONObject(json.toString());

                        JSONArray jsonRelays = jsonNodeInfo.getJSONArray("relays");

                        if (jsonRelays.length() > 0)
                        {
                            mNode.ipAddress = jsonRelays.getJSONObject(0).getJSONArray("or_addresses").getString(0).split(":")[0];
                            mNode.country = jsonRelays.getJSONObject(0).getString("country_name");
                            mNode.organization = jsonRelays.getJSONObject(0).getString("as_name");

                            StringBuffer sbInfo = new StringBuffer();
                            sbInfo.append(mNode.ipAddress);

                            if (mNode.country != null)
                                sbInfo.append(' ').append(mNode.country);

                            if (mNode.organization != null)
                                sbInfo.append(" (").append(mNode.organization).append(')');

                            mService.logNotice(sbInfo.toString());

                        }

                        reader.close();
                        is.close();

                        break;

                    } catch (Exception e) {

                        mService.debug ("Error getting node details from onionoo: " + e.getMessage());


                    }
                }
            }
        }


    }

    private String parseNodeName(String node)
    {
        if (node.indexOf('=')!=-1)
        {
            return (node.substring(node.indexOf("=")+1));
        }
        else if (node.indexOf('~')!=-1)
        {
            return (node.substring(node.indexOf("~")+1));
        }
        else
            return node;
    }
}
