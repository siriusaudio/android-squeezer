package uk.org.ngo.squeezer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.model.JiveItem;
import uk.org.ngo.squeezer.model.SlimCommand;

import uk.org.ngo.squeezer.model.Action;
import uk.org.ngo.squeezer.service.ISqueezeService;
import uk.org.ngo.squeezer.service.SqueezeService;
import uk.org.ngo.squeezer.util.ThemeManager;

public class PlayConfigActivity extends AppCompatActivity {
    
    private static class AlsaCard {
        String id;
        String name;
        String shortId;
        
        AlsaCard(String id, String name) {
            this.id = id;
            this.name = name;
            this.shortId = null;
        }
        
        AlsaCard(String id, String name, String shortId) {
            this.id = id;
            this.name = name;
            this.shortId = shortId;
        }
        
        @Override
        public String toString() {
            if (id.equals("default")) return name;
            // Display name with shortId and the hw:INDEX,0 format
            String displayName = name;
            if (shortId != null) {
                displayName += " (" + shortId + ")";
            }
            displayName += " [" + id + "]";
            return displayName;
        }
    }
    private static final String TAG = "PlayConfigActivity";
    
    private final ThemeManager mThemeManager = new ThemeManager();
    private ISqueezeService service = null;
    private boolean dataLoaded = false;
    private boolean cardsLoaded = false;
    private boolean configLoaded = false;
    
    private Spinner dsdRateSpinner;
    private Spinner convertOptionsSpinner;
    private Spinner pcmRateSpinner;
    private Spinner alsaCardSpinner;
    private Spinner dsdBaseSpinner;
    private List<AlsaCard> alsaCards = new ArrayList<>();
    private CheckBox useMmapCheck;
    private EditText phaseEdit;
    private CheckBox extremeModeCheck;
    private Button saveButton;
    private ProgressBar progressBar;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = (ISqueezeService) binder;
            // Load initial data
            refreshData();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mThemeManager.onCreate(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_config);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_playconfig_title);
        }
        
        initializeViews();
        setupListeners();
        
        bindService(new Intent(this, SqueezeService.class), serviceConnection,
                Context.BIND_AUTO_CREATE);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(serviceConnection);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        mThemeManager.onResume(this);
    }
    
    private void refreshData() {
        if (service != null && !dataLoaded) {
            dataLoaded = true;
            // Load ALSA cards first, then load config after cards are ready
            loadAlsaCards();
        }
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void initializeViews() {
        dsdRateSpinner = findViewById(R.id.dsd_rate_spinner);
        convertOptionsSpinner = findViewById(R.id.convert_options_spinner);
        pcmRateSpinner = findViewById(R.id.pcm_rate_spinner);
        alsaCardSpinner = findViewById(R.id.alsa_card_spinner);
        dsdBaseSpinner = findViewById(R.id.dsd_base_spinner);
        useMmapCheck = findViewById(R.id.use_mmap_check);
        phaseEdit = findViewById(R.id.phase_edit);
        extremeModeCheck = findViewById(R.id.extreme_mode_check);
        saveButton = findViewById(R.id.save_button);
        progressBar = findViewById(R.id.progress_bar);
        
        // Setup spinners
        ArrayAdapter<CharSequence> dsdRateAdapter = ArrayAdapter.createFromResource(this,
                R.array.dsd_rate_values, android.R.layout.simple_spinner_item);
        dsdRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dsdRateSpinner.setAdapter(dsdRateAdapter);
        
        ArrayAdapter<CharSequence> dsdBaseAdapter = ArrayAdapter.createFromResource(this,
                R.array.dsd_base_values, android.R.layout.simple_spinner_item);
        dsdBaseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dsdBaseSpinner.setAdapter(dsdBaseAdapter);

        ArrayAdapter<CharSequence> convertOptionsAdapter = ArrayAdapter.createFromResource(this,
            R.array.convert_options_values, android.R.layout.simple_spinner_item);
        convertOptionsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        convertOptionsSpinner.setAdapter(convertOptionsAdapter);

        ArrayAdapter<CharSequence> pcmRateAdapter = ArrayAdapter.createFromResource(this,
            R.array.pcm_rate_values, android.R.layout.simple_spinner_item);
        pcmRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pcmRateSpinner.setAdapter(pcmRateAdapter);
    }
    
    private void setupListeners() {
        saveButton.setOnClickListener(v -> saveConfig());
    }
    
    private void loadConfig() {
        if (service == null) {
            return;
        }
        
        setLoading(true);
        
        // Create command to get play config
        SlimCommand command = new SlimCommand();
        command.cmd.add("playconfig");
        command.cmd.add("get");
        
        // Create callback to handle response
        IServiceItemListCallback<JiveItem> callback = new IServiceItemListCallback<JiveItem>() {
            @Override
            public void onItemsReceived(int count, int start, Map<String, Object> parameters, List<JiveItem> items, Class<JiveItem> dataType) {
                if (configLoaded) {
                    return; // Already processed, ignore subsequent calls
                }
                configLoaded = true;
                runOnUiThread(() -> {
                    // Parse the response parameters which contain the config
                    if (parameters != null) {
                        parseAndLoadConfig(parameters);
                    }
                    setLoading(false);
                });
            }
            
            @Override
            public Object getClient() {
                return PlayConfigActivity.this;
            }
        };
        
        // Use service's requestItems method
        service.requestItems(command, callback);
    }
    
    private void parseAndLoadConfig(Map<String, Object> parameters) {
        try {
            // Extract config values from response parameters
            if (parameters.containsKey("dsd_rate")) {
                String dsdRate = String.valueOf(parameters.get("dsd_rate"));
                setSpinnerValue(dsdRateSpinner, dsdRate);
            }
            
            if (parameters.containsKey("conversion_method")) {
                String conversionMethod = String.valueOf(parameters.get("conversion_method"));
                setSpinnerValue(convertOptionsSpinner, conversionMethod);
            } else if (parameters.containsKey("convert_options")) {
                String convertOptions = String.valueOf(parameters.get("convert_options"));
                setSpinnerValue(convertOptionsSpinner, convertOptions);
            }

            if (parameters.containsKey("pcm_conversion_rate")) {
                String pcmRate = String.valueOf(parameters.get("pcm_conversion_rate"));
                setSpinnerValue(pcmRateSpinner, pcmRate);
            } else if (parameters.containsKey("pcm_rate")) {
                String pcmRate = String.valueOf(parameters.get("pcm_rate"));
                setSpinnerValue(pcmRateSpinner, pcmRate);
            }
            
            if (parameters.containsKey("alsa_card")) {
                String alsaCard = String.valueOf(parameters.get("alsa_card"));
                setAlsaCardSelection(alsaCard);
            }
            
            if (parameters.containsKey("dsd_base")) {
                String dsdBase = String.valueOf(parameters.get("dsd_base"));
                setSpinnerValue(dsdBaseSpinner, dsdBase);
            }
            
            if (parameters.containsKey("use_mmap")) {
                int useMmap = Integer.parseInt(String.valueOf(parameters.get("use_mmap")));
                useMmapCheck.setChecked(useMmap == 1);
            }
            
            if (parameters.containsKey("phase")) {
                phaseEdit.setText(String.valueOf(parameters.get("phase")));
            }
            
            if (parameters.containsKey("extreme_mode")) {
                int extremeMode = Integer.parseInt(String.valueOf(parameters.get("extreme_mode")));
                extremeModeCheck.setChecked(extremeMode == 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing config", e);
            Toast.makeText(this, "Error loading configuration", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void setSpinnerValue(Spinner spinner, String value) {
        for (int i = 0; i < spinner.getCount(); i++) {
            if (spinner.getItemAtPosition(i).toString().equals(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }
    
    private void loadAlsaCards() {
        if (service == null) {
            Log.e(TAG, "loadAlsaCards: Service not connected");
            return;
        }
        
        Log.d(TAG, "loadAlsaCards: Sending playconfig getalsacards command");
        
        SlimCommand command = new SlimCommand();
        command.cmd.add("playconfig");
        command.cmd.add("getalsacards");
        
        IServiceItemListCallback<JiveItem> callback = new IServiceItemListCallback<JiveItem>() {
            @Override
            public void onItemsReceived(int count, int start, Map<String, Object> parameters, List<JiveItem> items, Class<JiveItem> dataType) {
                if (cardsLoaded) {
                    return; // Already processed, ignore subsequent calls
                }
                cardsLoaded = true;
                Log.d(TAG, "loadAlsaCards callback: count=" + count + ", params=" + parameters);
                runOnUiThread(() -> {
                    parseAlsaCards(parameters);
                    // Load config after cards are loaded
                    loadConfig();
                });
            }
            
            @Override
            public Object getClient() {
                return PlayConfigActivity.this;
            }
        };
        
        service.requestItems(command, callback);
    }
    
    private void parseAlsaCards(Map<String, Object> parameters) {
        Log.d(TAG, "parseAlsaCards: parameters=" + parameters);
        alsaCards.clear();
        
        if (parameters != null && parameters.containsKey("cards_json")) {
            try {
                String cardsJson = String.valueOf(parameters.get("cards_json"));
                Log.d(TAG, "parseAlsaCards: cardsJson=" + cardsJson);
                JSONArray cardsArray = new JSONArray(cardsJson);
                Log.d(TAG, "parseAlsaCards: Found " + cardsArray.length() + " cards");
                
                for (int i = 0; i < cardsArray.length(); i++) {
                    JSONObject cardObj = cardsArray.getJSONObject(i);
                    String id = cardObj.getString("id");
                    String name = cardObj.getString("name");
                    String shortId = cardObj.optString("shortId", null);
                    Log.d(TAG, "parseAlsaCards: Adding card: " + name + " (" + id + "), shortId: " + shortId);
                    alsaCards.add(new AlsaCard(id, name, shortId));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing ALSA cards JSON", e);
            }
        } else {
            Log.w(TAG, "parseAlsaCards: No cards_json in parameters");
        }
        
        // If no cards received, add a default option
        if (alsaCards.isEmpty()) {
            Log.w(TAG, "parseAlsaCards: No cards parsed, adding default");
            alsaCards.add(new AlsaCard("default", "System Default"));
        }
        
        Log.d(TAG, "parseAlsaCards: Updating spinner with " + alsaCards.size() + " cards");
        
        // Update spinner adapter
        ArrayAdapter<AlsaCard> adapter = new ArrayAdapter<>(
            PlayConfigActivity.this,
            android.R.layout.simple_spinner_item,
            alsaCards
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alsaCardSpinner.setAdapter(adapter);
        
        Log.d(TAG, "parseAlsaCards: Spinner updated successfully");
    }
    
    private void setAlsaCardSelection(String cardId) {
        for (int i = 0; i < alsaCards.size(); i++) {
            AlsaCard card = alsaCards.get(i);
            // Match by hw:INDEX,0 format first, then try legacy formats
            if (card.id.equals(cardId)) {
                alsaCardSpinner.setSelection(i);
                return;
            }
            // Backward compatibility: match by shortId (card name like "H20")
            if (card.shortId != null && card.shortId.equals(cardId)) {
                alsaCardSpinner.setSelection(i);
                return;
            }
            // Backward compatibility: match by just the index number
            if (card.id.startsWith("hw:") && card.id.endsWith(",0")) {
                String cardNum = card.id.substring(3, card.id.length() - 2);
                if (cardNum.equals(cardId)) {
                    alsaCardSpinner.setSelection(i);
                    return;
                }
            }
        }
    }
    
    private String getSelectedAlsaCardId() {
        AlsaCard selected = (AlsaCard) alsaCardSpinner.getSelectedItem();
        if (selected == null) return "default";
        // Always return the index (id), not the shortId (name)
        return selected.id;
    }
    
    private void saveConfig() {
        if (service == null) {
            Toast.makeText(this, "Not connected to server", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            int phase = Integer.parseInt(phaseEdit.getText().toString());
            if (phase < 0 || phase > 100) {
                Toast.makeText(this, "Phase must be between 0 and 100", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid phase value", Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        // Create action to save play config
        Action.JsonAction action = new Action.JsonAction();
        action.cmd.add("playconfig");
        action.cmd.add("set");
        action.cmd.add("dsd_rate:" + dsdRateSpinner.getSelectedItem().toString());
        action.cmd.add("conversion_method:" + convertOptionsSpinner.getSelectedItem().toString());
        action.cmd.add("pcm_conversion_rate:" + pcmRateSpinner.getSelectedItem().toString());
        action.cmd.add("alsa_card:" + getSelectedAlsaCardId());
        action.cmd.add("dsd_base:" + dsdBaseSpinner.getSelectedItem().toString());
        action.cmd.add("use_mmap:" + (useMmapCheck.isChecked() ? "1" : "0"));
        action.cmd.add("phase:" + phaseEdit.getText().toString());
        action.cmd.add("extreme_mode:" + (extremeModeCheck.isChecked() ? "1" : "0"));
        
        service.action(action);
        
        // After save completes, restart backend services
        new android.os.Handler().postDelayed(() -> {
            restartBackendServices();
        }, 1000);
    }
    
    private void restartBackendServices() {
        if (service == null) {
            setLoading(false);
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Stop sirius_listen_native.service
        Action.JsonAction stopNativeAction = new Action.JsonAction();
        stopNativeAction.cmd.add("sudo");
        stopNativeAction.cmd.add("systemctl");
        stopNativeAction.cmd.add("stop");
        stopNativeAction.cmd.add("sirius_listen_native.service");
        service.action(stopNativeAction);
        
        // Stop sirius_listen_pcm.service
        Action.JsonAction stopPcmAction = new Action.JsonAction();
        stopPcmAction.cmd.add("sudo");
        stopPcmAction.cmd.add("systemctl");
        stopPcmAction.cmd.add("stop");
        stopPcmAction.cmd.add("sirius_listen_pcm.service");
        service.action(stopPcmAction);
        
        // Restart sirius_player.service
        Action.JsonAction restartPlayerAction = new Action.JsonAction();
        restartPlayerAction.cmd.add("sudo");
        restartPlayerAction.cmd.add("systemctl");
        restartPlayerAction.cmd.add("restart");
        restartPlayerAction.cmd.add("sirius_player.service");
        service.action(restartPlayerAction);
        
        // Update UI after services restart
        new android.os.Handler().postDelayed(() -> {
            setLoading(false);
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        }, 2000);
    }
    
    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!loading);
        dsdRateSpinner.setEnabled(!loading);
        convertOptionsSpinner.setEnabled(!loading);
        pcmRateSpinner.setEnabled(!loading);
        alsaCardSpinner.setEnabled(!loading);
        dsdBaseSpinner.setEnabled(!loading);
        useMmapCheck.setEnabled(!loading);
        phaseEdit.setEnabled(!loading);
        extremeModeCheck.setEnabled(!loading);
    }
    
    public static void show(Context context) {
        final Intent intent = new Intent(context, PlayConfigActivity.class);
        context.startActivity(intent);
    }
}
