package xink.vpn;

import static xink.vpn.Constants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xink.vpn.editor.EditAction;
import xink.vpn.editor.VpnProfileEditor;
import xink.vpn.wrapper.KeyStore;
import xink.vpn.wrapper.VpnProfile;
import xink.vpn.wrapper.VpnState;
import xink.vpn.wrapper.VpnType;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.SimpleAdapter;
import android.widget.SimpleAdapter.ViewBinder;
import android.widget.TextView;
import android.widget.ToggleButton;

public class VpnSettings extends Activity {

    private static final String ROWITEM_KEY = "vpn"; //$NON-NLS-1$
    private static final String TAG = "xink"; //$NON-NLS-1$

    // 同一行的所有控件绑定同一份数据
    private static final String[] VPN_VIEW_KEYS = new String[] { ROWITEM_KEY, ROWITEM_KEY, ROWITEM_KEY };
    private static final int[] VPN_VIEWS = new int[] { R.id.radioActive, R.id.tgbtnConn, R.id.txtStateMsg };

    private VpnProfileRepository repository;
    private ListView vpnListView;
    private List<Map<String, VpnViewItem>> vpnListViewContent;
    private VpnViewBinder vpnViewBinder = new VpnViewBinder();
    private VpnViewItem activeVpnItem;
    private SimpleAdapter vpnListAdapter;
    private VpnActor actor;
    private BroadcastReceiver stateBroadcastReceiver;
    private KeyStore keyStore;
    private Runnable resumeAction;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        repository = VpnProfileRepository.getInstance(getApplicationContext());
        actor = new VpnActor(getApplicationContext());
        keyStore = new KeyStore(getApplicationContext());

        setTitle(R.string.selectVpn);
        setContentView(R.layout.vpn_list);

        ((TextView) findViewById(R.id.btnAddVpn)).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(final View v) {
                onAddVpn();
            }
        });

        vpnListViewContent = new ArrayList<Map<String, VpnViewItem>>();
        vpnListView = (ListView) findViewById(R.id.listVpns);
        buildVpnListView();

        registerReceivers();
        checkAllVpnStatus();
    }

    private void checkAllVpnStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                actor.checkAllStatus();
            }
        }, "vpn-state-checker").start(); //$NON-NLS-1$
    }

    private void buildVpnListView() {
        loadContent();

        vpnListAdapter = new SimpleAdapter(this, vpnListViewContent, R.layout.vpn_profile, VPN_VIEW_KEYS, VPN_VIEWS);
        vpnListAdapter.setViewBinder(vpnViewBinder);
        vpnListView.setAdapter(vpnListAdapter);
        registerForContextMenu(vpnListView);
    }

    private void loadContent() {
        vpnListViewContent.clear();
        activeVpnItem = null;

        String activeProfileId = repository.getActiveProfileId();
        List<VpnProfile> allVpnProfiles = repository.getAllVpnProfiles();

        for (VpnProfile vpnProfile : allVpnProfiles) {
            addToVpnListView(activeProfileId, vpnProfile);
        }
    }

    private void addToVpnListView(final String activeProfileId, final VpnProfile vpnProfile) {
        if (vpnProfile == null) {
            return;
        }

        VpnViewItem item = makeVpnViewItem(activeProfileId, vpnProfile);

        Map<String, VpnViewItem> row = new HashMap<String, VpnViewItem>();
        row.put(ROWITEM_KEY, item);

        vpnListViewContent.add(row);
    }

    private VpnViewItem makeVpnViewItem(final String activeProfileId, final VpnProfile vpnProfile) {
        VpnViewItem item = new VpnViewItem();
        item.profile = vpnProfile;

        if (vpnProfile.getId().equals(activeProfileId)) {
            item.isActive = true;
            activeVpnItem = item;
        }
        return item;
    }

    @Override
    public void onCreateContextMenu(final ContextMenu menu, final View v, final ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.vpn_list_context_menu, menu);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        VpnViewItem selectedVpnItem = getVpnViewItemAt(info.position);
        VpnProfile p = selectedVpnItem.profile;

        menu.setHeaderTitle(p.getName());

        if (p.getState() == VpnState.CONNECTED) {
            // 已经连通的VPN不允许修改
            menu.findItem(R.id.menu_edit_vpn).setEnabled(false);
            menu.findItem(R.id.menu_del_vpn).setEnabled(false);
        }
    }

    @SuppressWarnings("unchecked")
    private VpnViewItem getVpnViewItemAt(final int pos) {
        return ((Map<String, VpnViewItem>) vpnListAdapter.getItem(pos)).get(ROWITEM_KEY);
    }

    @Override
    public boolean onContextItemSelected(final MenuItem item) {
        boolean consumed = false;
        int itemId = item.getItemId();
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        VpnViewItem vpnItem = getVpnViewItemAt(info.position);

        switch (itemId) {
        case R.id.menu_del_vpn:
            onDeleteVpn(vpnItem);
            consumed = true;
            break;
        case R.id.menu_edit_vpn:
            onEditVpn(vpnItem);
            consumed = true;
            break;
        default:
            consumed = super.onContextItemSelected(item);
            break;
        }

        return consumed;
    }

    private void onAddVpn() {
        startActivityForResult(new Intent(this, VpnTypeSelection.class), REQ_SELECT_VPN_TYPE);
    }

    private void onDeleteVpn(final VpnViewItem vpnItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(android.R.drawable.ic_dialog_alert).setTitle(android.R.string.dialog_alert_title).setMessage(R.string.del_vpn_confirm);
        builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(final DialogInterface dialog, final int which) {
                repository.deleteVpnProfile(vpnItem.profile);
                buildVpnListView();
            }

        }).setNegativeButton(android.R.string.no, null).show();
    }

    private void onEditVpn(final VpnViewItem vpnItem) {
        Log.d(TAG, "onEditVpn"); //$NON-NLS-1$

        VpnProfile p = vpnItem.profile;
        editVpn(p);
    }

    private void editVpn(final VpnProfile p) {
        VpnType type = p.getType();

        Class<? extends VpnProfileEditor> editorClass = type.getEditorClass();
        if (editorClass == null) {
            Log.d(TAG, "editor class is null for " + type); //$NON-NLS-1$
            return;
        }

        Intent intent = new Intent(this, editorClass);
        intent.setAction(EditAction.EDIT.toString());
        intent.putExtra(KEY_VPN_PROFILE_NAME, p.getName());
        startActivityForResult(intent, REQ_EDIT_VPN);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.vpn_list_menu, menu);

        menu.findItem(R.id.menu_about).setIcon(android.R.drawable.ic_menu_info_details);
        menu.findItem(R.id.menu_help).setIcon(android.R.drawable.ic_menu_help);

        return true;
    }

    /**
     * Handles item selections
     */
    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        boolean consumed = true;
        int itemId = item.getItemId();

        switch (itemId) {
        case R.id.menu_about:
            showDialog(DLG_ABOUT);
            break;
        case R.id.menu_help:
            openWikiHome();
            break;
        default:
            consumed = super.onContextItemSelected(item);
            break;
        }

        return consumed;
    }

    private void openWikiHome() {
        openUrl(getString(R.string.url_wiki_home));
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (data == null) {
            return;
        }

        switch (requestCode) {
        case REQ_SELECT_VPN_TYPE:
            onVpnTypePicked(data);
            break;
        case REQ_ADD_VPN:
            if (!keyStore.isUnlocked()) {
                resumeAction = new Runnable() {
                    @Override
                    public void run() {
                        // redo this after unlock activity return
                        onActivityResult(requestCode, resultCode, data);
                    }
                };

                Log.i(TAG, "keystore is unlocked, unlock it now");
                keyStore.unlock(this);
            } else {
                onVpnProfileAdded(data);
            }

            break;
        case REQ_EDIT_VPN:
            new KeyStore(this).isUnlocked();
            onVpnProfileEdited(data);
            break;
        default:
            Log.w(TAG, "onActivityResult, unknown reqeustCode " + requestCode + ", result=" + resultCode + ", data=" + data); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            break;
        }
    }

    private void onVpnTypePicked(final Intent data) {
        VpnType pickedVpnType = (VpnType) data.getExtras().get(KEY_VPN_TYPE);
        addVpn(pickedVpnType);
    }

    private void addVpn(final VpnType vpnType) {
        Log.i(TAG, "add vpn " + vpnType); //$NON-NLS-1$
        Class<? extends VpnProfileEditor> editorClass = vpnType.getEditorClass();

        if (editorClass == null) {
            Log.d(TAG, "editor class is null for " + vpnType); //$NON-NLS-1$
            return;
        }

        Intent intent = new Intent(this, editorClass);
        intent.setAction(EditAction.CREATE.toString());
        startActivityForResult(intent, REQ_ADD_VPN);
    }

    private void onVpnProfileAdded(final Intent data) {
        Log.i(TAG, "new vpn profile created"); //$NON-NLS-1$

        String name = data.getStringExtra(KEY_VPN_PROFILE_NAME);
        VpnProfile profile = repository.getProfileByName(name);

        Log.i(TAG, "contains key? " + new KeyStore(this).contains(profile));

        addToVpnListView(repository.getActiveProfileId(), profile);
        refreshVpnListView();
    }

    private void onVpnProfileEdited(final Intent data) {
        Log.i(TAG, "vpn profile modified"); //$NON-NLS-1$
        refreshVpnListView();
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_VPN_CONNECTIVITY);
        filter.addAction(ACT_TOGGLE_VPN_CONN);
        stateBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                String action = intent.getAction();

                if (ACT_TOGGLE_VPN_CONN.equals(action)) {
                } else if (ACTION_VPN_CONNECTIVITY.equals(action)) {
                    onStateChanged(intent);
                } else {
                    Log.w(TAG, "VpnStateReceiver ignores unknown intent:" + intent); //$NON-NLS-1$
                }
            }
        };
        registerReceiver(stateBroadcastReceiver, filter);
    }

    private void onStateChanged(final Intent intent) {
        Log.d(TAG, "onStateChanged: " + intent); //$NON-NLS-1$

        final String profileName = intent.getStringExtra(BROADCAST_PROFILE_NAME);
        final VpnState state = VpnActor.extractVpnState(intent);
        final int err = intent.getIntExtra(BROADCAST_ERROR_CODE, VPN_ERROR_NO_ERROR);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stateChanged(profileName, state, err);
            }
        });
    }

    private void stateChanged(final String profileName, final VpnState state, final int errCode) {
        Log.d(TAG, "stateChanged, '" + profileName + "', state: " + state + ", errCode=" + errCode); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        VpnProfile p = repository.getProfileByName(profileName);

        if (p == null) {
            Log.w(TAG, profileName + " NOT found"); //$NON-NLS-1$
            return;
        }

        p.setState(state);
        refreshVpnListView();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "VpnSettings onDestroy"); //$NON-NLS-1$
        unregisterReceivers();

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "VpnSettings onResume, check and run resume action");
        if (resumeAction != null) {
            Runnable action = resumeAction;
            resumeAction = null;
            runOnUiThread(action);
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "VpnSettings onPause"); //$NON-NLS-1$
        save();

        super.onPause();
    }

    private void save() {
        repository.save();
    }

    private void unregisterReceivers() {
        if (stateBroadcastReceiver != null) {
            unregisterReceiver(stateBroadcastReceiver);
        }
    }

    private void vpnItemActivated(final VpnViewItem activatedItem) {
        if (activeVpnItem == activatedItem) {
            return;
        }

        if (activeVpnItem != null) {
            activeVpnItem.isActive = false;
        }

        activeVpnItem = activatedItem;
        actor.activate(activeVpnItem.profile);
        refreshVpnListView();
    }

    private void refreshVpnListView() {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                vpnListAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected Dialog onCreateDialog(final int id) {
        switch (id) {
        case DLG_ABOUT:
            return createAboutDialog();
        default:
            break;
        }
        return null;
    }

    private Dialog createAboutDialog() {
        AlertDialog.Builder builder;

        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.about, (ViewGroup) findViewById(R.id.aboutRoot));

        builder = new AlertDialog.Builder(this);
        builder.setView(layout).setTitle(getString(R.string.about));

        bindPackInfo(layout);

        ImageView imgPaypal = (ImageView) layout.findViewById(R.id.imgPaypalDonate);
        imgPaypal.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                openUrl(getString(R.string.url_paypal_donate));
            }
        });

        return builder.create();
    }

    private void bindPackInfo(final View layout) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView txtVer = (TextView) layout.findViewById(R.id.txtVersion);
            txtVer.setText(getString(R.string.pack_ver, getString(R.string.app_name), info.versionName));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "get pack info failed", e); //$NON-NLS-1$
        }
    }

    private void openUrl(final String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    final class VpnViewBinder implements ViewBinder {

        @Override
        public boolean setViewValue(final View view, final Object data, final String textRepresentation) {
            if (!(data instanceof VpnViewItem)) {
                return false;
            }

            VpnViewItem item = (VpnViewItem) data;
            boolean bound = true;

            if (view instanceof RadioButton) {
                bindVpnItem((RadioButton) view, item);
            } else if (view instanceof ToggleButton) {
                bindVpnState((ToggleButton) view, item);
            } else if (view instanceof TextView) {
                bindVpnStateMsg(((TextView) view), item);
            } else {
                bound = false;
                Log.d(TAG, "unknown view, not bound: v=" + view + ", data=" + textRepresentation); //$NON-NLS-1$ //$NON-NLS-2$
            }

            return bound;
        }

        private void bindVpnItem(final RadioButton view, final VpnViewItem item) {
            view.setOnCheckedChangeListener(null);

            view.setText(item.profile.getName());
            view.setChecked(item.isActive);

            view.setOnCheckedChangeListener(item);
        }

        private void bindVpnState(final ToggleButton view, final VpnViewItem item) {
            view.setOnCheckedChangeListener(null);

            VpnState state = item.profile.getState();
            view.setChecked(state == VpnState.CONNECTED);
            view.setEnabled(VpnActor.isInStableState(item.profile));

            view.setOnCheckedChangeListener(item);
        }

        private void bindVpnStateMsg(final TextView textView, final VpnViewItem item) {
            VpnState state = item.profile.getState();
            String txt = getStateText(state);
            textView.setVisibility(TextUtils.isEmpty(txt) ? View.INVISIBLE : View.VISIBLE);
            textView.setText(txt);
        }

        private String getStateText(final VpnState state) {
            String txt = ""; //$NON-NLS-1$
            switch (state) {
            case CONNECTING:
                txt = getString(R.string.connecting);
                break;
            case DISCONNECTING:
                txt = getString(R.string.disconnecting);
                break;
            }

            return txt;
        }
    }

    final class VpnViewItem implements OnCheckedChangeListener {
        VpnProfile profile;
        boolean isActive;

        @Override
        public void onCheckedChanged(final CompoundButton button, final boolean isChecked) {

            if (button instanceof RadioButton) {
                onActivationChanged(isChecked);
            } else if (button instanceof ToggleButton) {
                toggleState(isChecked);
            }
        }

        private void onActivationChanged(final boolean isChecked) {
            if (isActive == isChecked) {
                return;
            }

            isActive = isChecked;

            if (isActive) {
                vpnItemActivated(this);
            }
        }

        private void toggleState(final boolean isChecked) {
            if (isChecked) {
                actor.connect(profile);
            } else {
                actor.disconnect();
            }
        }

        @Override
        public String toString() {
            return profile.getName();
        }
    }
}
