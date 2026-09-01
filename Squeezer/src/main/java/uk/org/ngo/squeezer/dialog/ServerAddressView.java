/*
 * Copyright (c) 2012 Google Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer.dialog;

import android.content.Context;
import android.text.Editable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout.LayoutParams;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import uk.org.ngo.squeezer.Preferences;
import uk.org.ngo.squeezer.R;
import uk.org.ngo.squeezer.Squeezer;
import uk.org.ngo.squeezer.Util;
import uk.org.ngo.squeezer.util.AfterTextChangedLister;
import uk.org.ngo.squeezer.util.ScanNetworkTask;

/**
 * Scans the local network for servers, allow the user to choose one, set it as the preferred server
 * for this network, and optionally enter authentication information.
 * <p>
 * A new network scan can be initiated manually if desired.
 */
public class ServerAddressView extends LinearLayout implements ScanNetworkTask.ScanNetworkCallback {
    private static final class DiscoveredServer {
        final String name;
        final String address;
        final String label;

        DiscoveredServer(String name, String address) {
            this.name = name;
            this.address = address;
            this.label = name + " (" + address + ")";
        }
    }

    private Preferences preferences;
    private Preferences.ServerAddress serverAddress;

    private EditText serverAddressEditText;
    private EditText userNameEditText;
    private EditText passwordEditText;
    private MaterialCheckBox wakeOnLan;
    private TextInputLayout macLayout;
    private boolean macDirty;
    private EditText macEditText;
    private View scanProgress;
    private LinearLayout visibleServersContainer;
    private View refreshScanButton;
    private String selectedServerAddress;

    private ScanNetworkTask scanNetworkTask;

    private List<DiscoveredServer> discoveredServers;

    public ServerAddressView(final Context context) {
        super(context);
        initialize();
    }

    public ServerAddressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        inflate(getContext(), R.layout.server_address_view, this);
        if (!isInEditMode()) {
            Squeezer.getPreferences(prefs -> {
                preferences = prefs;
                serverAddress = preferences.getServerAddress();
                if (serverAddress.localAddress() == null) {
                    Preferences.ServerAddress cliServerAddress = preferences.getCliServerAddress();
                    if (cliServerAddress.localAddress() != null) {
                        serverAddress.setAddress(cliServerAddress.localHost());
                    }
                }

                serverAddressEditText = findViewById(R.id.server_address);
                userNameEditText = findViewById(R.id.username);
                passwordEditText = findViewById(R.id.password);

                wakeOnLan = findViewById(R.id.wol);
                wakeOnLan.setOnCheckedChangeListener((compoundButton, b) -> macLayout.setVisibility(b ? VISIBLE : GONE));
                macLayout = findViewById(R.id.mac_til);
                macEditText = findViewById(R.id.mac);
                macLayout.setEndIconOnClickListener(view -> {
                    FragmentManager fragmentManager = ((AppCompatActivity) getContext()).getSupportFragmentManager();
                    InfoDialog.show(fragmentManager, R.string.settings_MAC_label, R.string.settings_MAC_info);
                });
                macLayout.setErrorIconOnClickListener(view -> {
                    FragmentManager fragmentManager = ((AppCompatActivity) getContext()).getSupportFragmentManager();
                    InfoDialog.show(fragmentManager, R.string.settings_MAC_label, R.string.settings_MAC_info);
                });
                macEditText.setOnFocusChangeListener((view, b) -> {
                    if (!b) {
                        checkMac();
                    }
                });
                macEditText.addTextChangedListener(new AfterTextChangedLister() {
                    @Override
                    public void afterTextChanged(Editable editable) {
                        if (macDirty) {
                            macLayout.setError(Util.validateMac(editable.toString()) ? null : getResources().getString(R.string.settings_invalid_MAC));
                        }
                    }
                });

                scanProgress = findViewById(R.id.scan_progress);
                visibleServersContainer = findViewById(R.id.visible_servers_container);
                refreshScanButton = findViewById(R.id.refresh_scan_button);
                refreshScanButton.setOnClickListener(view -> startNetworkScan());
                setServerAddress(serverAddress.localAddress());

                startNetworkScan();
            });
        }
    }

    private boolean checkMac() {
        macDirty = true;
        String mac = macEditText.getText().toString();
        boolean macOk = Util.validateMac(mac);
        macLayout.setError(macOk ? null : "Invalid MAC address");
        return macOk;
    }

    public boolean savePreferences() {
        if (wakeOnLan.isChecked() && !checkMac()) {
            return false;
        }

        String address = serverAddressEditText.getText().toString();
        serverAddress.setAddress(address);
        serverAddress.setServerName(getServerName(address));
        serverAddress.userName = userNameEditText.getText().toString();
        serverAddress.password = passwordEditText.getText().toString();
        serverAddress.wakeOnLan = wakeOnLan.isChecked();
        serverAddress.mac = Util.parseMac(macEditText.getText().toString());
        preferences.saveServerAddress(serverAddress);

        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        // Stop scanning
        if (scanNetworkTask != null) {
            scanNetworkTask.cancel();
        }

        super.onDetachedFromWindow();
    }

    /**
     * Starts scanning for servers.
     */
    private void startNetworkScan() {
        View scanContainer = findViewById(R.id.scan_progress_container);
        ImageView scanLogo = findViewById(R.id.scan_logo);
        
        scanContainer.setVisibility(VISIBLE);
        refreshScanButton.setEnabled(false);
        
        // Start pulsing animation on the logo
        if (scanLogo != null) {
            Animation pulseAnim = AnimationUtils.loadAnimation(getContext(), R.anim.pulse_animation);
            scanLogo.startAnimation(pulseAnim);
        }

        visibleServersContainer.removeAllViews();
        TextView loading = new TextView(getContext());
        loading.setText(R.string.settings_server_scan_progress);
        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                8,
                getResources().getDisplayMetrics());
        loading.setPadding(padding, padding, padding, padding);
        visibleServersContainer.addView(loading);
        scanNetworkTask = new ScanNetworkTask(getContext(), this);
        new Thread(scanNetworkTask).start();
    }

    /**
     * Called when server scanning has finished.
     * @param serverMap Discovered servers, key is the server name, value is the IP address.
     */
    public void onScanFinished(Map<String, String> serverMap) {
        scanNetworkTask = null;

        View scanContainer = findViewById(R.id.scan_progress_container);
        ImageView scanLogo = findViewById(R.id.scan_logo);
        
        scanContainer.setVisibility(INVISIBLE);
        
        // Stop animation
        if (scanLogo != null) {
            scanLogo.clearAnimation();
        }
        refreshScanButton.setEnabled(true);

        discoveredServers = new ArrayList<>();
        for (Entry<String, String> entry : serverMap.entrySet()) {
            DiscoveredServer discoveredServer = new DiscoveredServer(entry.getKey(), entry.getValue());
            discoveredServers.add(discoveredServer);
        }

        renderVisibleServers();

        // First look for the stored server name in the list of found servers
        String addressOfStoredServerName = getServerAddress(serverAddress.serverName());
        int position = getServerPosition(addressOfStoredServerName);

        // If that fails, look for the stored server address in the list of found servers
        if (position < 0) position = getServerPosition(serverAddress.localAddress());

        if (position >= 0 && position < discoveredServers.size()) {
            setServerAddress(discoveredServers.get(position).address);
        } else {
            renderVisibleServers();
        }
    }

    private void setServerAddress(String address) {
        selectedServerAddress = address;
        serverAddress = preferences.getServerAddress(address);

        serverAddressEditText.setText(serverAddress.localAddress());
        userNameEditText.setText(serverAddress.userName);
        passwordEditText.setText(serverAddress.password);
        wakeOnLan.setChecked(serverAddress.wakeOnLan);
        macLayout.setVisibility(serverAddress.wakeOnLan ? VISIBLE : GONE);
        macEditText.setText(Util.formatMac(serverAddress.mac));

        if (discoveredServers != null) {
            renderVisibleServers();
        }
    }

    private void renderVisibleServers() {
        visibleServersContainer.removeAllViews();

        if (discoveredServers == null || discoveredServers.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(R.string.settings_server_scan_empty);
            int padding = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    8,
                    getResources().getDisplayMetrics());
            empty.setPadding(padding, padding, padding, padding);
            visibleServersContainer.addView(empty);
            return;
        }

        int horizontalPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                14,
                getResources().getDisplayMetrics());
        int verticalPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12,
                getResources().getDisplayMetrics());

        for (DiscoveredServer discoveredServer : discoveredServers) {
            LinearLayout serverRow = new LinearLayout(getContext());
            serverRow.setOrientation(LinearLayout.VERTICAL);
            serverRow.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            serverRow.setBackgroundResource(R.drawable.server_choice_frame);
            serverRow.setClickable(true);
            serverRow.setFocusable(true);
            serverRow.setSelected(discoveredServer.address.equals(selectedServerAddress));
            serverRow.setOnClickListener(view -> setServerAddress(discoveredServer.address));

            LayoutParams rowLayoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowLayoutParams.bottomMargin = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    8,
                    getResources().getDisplayMetrics());
            serverRow.setLayoutParams(rowLayoutParams);

            TextView nameView = new TextView(getContext());
            nameView.setText(discoveredServer.name);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);

            TextView addressView = new TextView(getContext());
            addressView.setText(discoveredServer.address);
            addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            addressView.setPadding(0, verticalPadding / 3, 0, 0);

            serverRow.addView(nameView);
            serverRow.addView(addressView);
            visibleServersContainer.addView(serverRow);
        }
    }

    private String getServerName(String ipPort) {
        if (ipPort == null || discoveredServers == null) {
            return null;
        }

        for (DiscoveredServer discoveredServer : discoveredServers) {
            if (ipPort.equals(discoveredServer.address)) {
                return discoveredServer.name;
            }
        }
        return null;
    }

    private int getServerPosition(String host) {
        if (host == null || discoveredServers == null) {
            return -1;
        }

        for (int position = 0; position < discoveredServers.size(); position++) {
            if (host.equals(discoveredServers.get(position).address)) {
                return position;
            }
        }
        return -1;
    }

    private String getServerAddress(String serverName) {
        if (serverName == null || discoveredServers == null) {
            return null;
        }

        for (DiscoveredServer discoveredServer : discoveredServers) {
            if (serverName.equals(discoveredServer.name)) {
                return discoveredServer.address;
            }
        }
        return null;
    }

}
