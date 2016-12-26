/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.fragment;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.farmerbb.taskbar.BuildConfig;
import com.farmerbb.taskbar.MainActivity;
import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.activity.ClearDataActivity;
import com.farmerbb.taskbar.activity.ClearDataActivityDark;
import com.farmerbb.taskbar.activity.HomeActivity;
import com.farmerbb.taskbar.activity.IconPackActivity;
import com.farmerbb.taskbar.activity.IconPackActivityDark;
import com.farmerbb.taskbar.activity.KeyboardShortcutActivity;
import com.farmerbb.taskbar.activity.SelectAppActivity;
import com.farmerbb.taskbar.activity.SelectAppActivityDark;
import com.farmerbb.taskbar.service.DashboardService;
import com.farmerbb.taskbar.service.NotificationService;
import com.farmerbb.taskbar.service.StartMenuService;
import com.farmerbb.taskbar.service.TaskbarService;
import com.farmerbb.taskbar.util.FreeformHackHelper;
import com.farmerbb.taskbar.util.U;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

import yuku.ambilwarna.widget.AmbilWarnaPreference;

public class SettingsFragment extends PreferenceFragment implements OnPreferenceClickListener {

    boolean finishedLoadingPrefs;
    boolean showReminderToast = false;
    boolean restartNotificationService = false;
    int noThanksCount = 0;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Remove dividers
        View rootView = getView();
        if(rootView != null) {
            ListView list = (ListView) rootView.findViewById(android.R.id.list);
            if(list != null) list.setDivider(null);
        }

        // Set values
        setRetainInstance(true);
        setHasOptionsMenu(true);

        // On smaller-screened devices, set "Grid" as the default start menu layout
        SharedPreferences pref = U.getSharedPreferences(getActivity());
        if(getActivity().getApplicationContext().getResources().getConfiguration().smallestScreenWidthDp < 600
                && pref.getString("start_menu_layout", "null").equals("null")) {
            pref.edit().putString("start_menu_layout", "grid").apply();
        }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if(!pref.getBoolean("freeform_hack_override", false)) {
                pref.edit()
                        .putBoolean("freeform_hack", U.hasFreeformSupport(getActivity()))
                        .putBoolean("freeform_hack_override", true)
                        .apply();
            } else if(!U.hasFreeformSupport(getActivity())) {
                pref.edit().putBoolean("freeform_hack", false).apply();

                LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(new Intent("com.farmerbb.taskbar.FINISH_FREEFORM_ACTIVITY"));
            }
        }
    }

    private Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = new Preference.OnPreferenceChangeListener() {
        @SuppressLint("CommitPrefEdits")
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            String stringValue = value.toString();

            if(preference instanceof ListPreference) {
                // For list preferences, look up the correct display value in
                // the preference's 'entries' list.
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(stringValue);

                // Set the summary to reflect the new value.
                preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);

                if(finishedLoadingPrefs && preference.getKey().equals("theme")) {
                    // Restart MainActivity
                    Intent intent = new Intent(getActivity(), MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra("theme_change", true);
                    startActivity(intent);
                    getActivity().overridePendingTransition(0, 0);
                }

            } else if(!(preference instanceof CheckBoxPreference || preference instanceof AmbilWarnaPreference)) {
                // For all other preferences, set the summary to the value's
                // simple string representation.
                preference.setSummary(stringValue);
            }

            if(finishedLoadingPrefs) restartTaskbar();

            return true;
        }
    };

    void bindPreferenceSummaryToValue(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        if(!(preference instanceof CheckBoxPreference || preference instanceof AmbilWarnaPreference))
            sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                    PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), ""));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // Override default Android "up" behavior to instead mimic the back button
                getActivity().onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @SuppressLint("SetTextI18n")
    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public boolean onPreferenceClick(final Preference p) {
        final SharedPreferences pref = U.getSharedPreferences(getActivity());

        switch(p.getKey()) {
            case "clear_pinned_apps":
                Intent clearIntent = null;

                switch(pref.getString("theme", "light")) {
                    case "light":
                        clearIntent = new Intent(getActivity(), ClearDataActivity.class);
                        break;
                    case "dark":
                        clearIntent = new Intent(getActivity(), ClearDataActivityDark.class);
                        break;
                }

                startActivity(clearIntent);
                break;
            case "enable_recents":
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    U.showErrorDialog(getActivity(), "GET_USAGE_STATS");
                }
                break;
            case "launcher":
                if(canDrawOverlays()) {
                    ComponentName component = new ComponentName(getActivity(), HomeActivity.class);
                    getActivity().getPackageManager().setComponentEnabledSetting(component,
                            ((CheckBoxPreference) p).isChecked() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                } else {
                    U.showPermissionDialog(getActivity());
                    ((CheckBoxPreference) p).setChecked(false);
                }

                if(!((CheckBoxPreference) p).isChecked())
                    LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(new Intent("com.farmerbb.taskbar.KILL_HOME_ACTIVITY"));
                break;
            case "keyboard_shortcut":
                ComponentName component = new ComponentName(getActivity(), KeyboardShortcutActivity.class);
                getActivity().getPackageManager().setComponentEnabledSetting(component,
                        ((CheckBoxPreference) p).isChecked() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                break;
            case "about":
                U.checkForUpdates(getActivity());
                break;
            case "freeform_hack":
                if(((CheckBoxPreference) p).isChecked()) {
                    if(!U.hasFreeformSupport(getActivity())) {
                        ((CheckBoxPreference) p).setChecked(false);

                        AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                        builder2.setTitle(R.string.freeform_dialog_title)
                                .setMessage(R.string.freeform_dialog_message)
                                .setPositiveButton(R.string.action_developer_options, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        showReminderToast = true;

                                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                                        try {
                                            startActivity(intent);
                                            U.showToastLong(getActivity(), R.string.enable_force_activities_resizable);
                                        } catch (ActivityNotFoundException e) {
                                            intent = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
                                            try {
                                                startActivity(intent);
                                                U.showToastLong(getActivity(), R.string.enable_developer_options);
                                            } catch (ActivityNotFoundException e2) { /* Gracefully fail */ }
                                        }
                                    }
                                });

                        AlertDialog dialog2 = builder2.create();
                        dialog2.show();
                        dialog2.setCancelable(false);
                    }

                    if(pref.getBoolean("taskbar_active", false)
                            && getActivity().isInMultiWindowMode()
                            && !FreeformHackHelper.getInstance().isFreeformHackActive()) {
                        U.startFreeformHack(getActivity(), false, false);
                    }
                } else {
                    LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(new Intent("com.farmerbb.taskbar.FINISH_FREEFORM_ACTIVITY"));
                    LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(new Intent("com.farmerbb.taskbar.FORCE_TASKBAR_RESTART"));
                }

                findPreference("open_in_fullscreen").setEnabled(((CheckBoxPreference) p).isChecked());
                findPreference("save_window_sizes").setEnabled(((CheckBoxPreference) p).isChecked());
                findPreference("window_size").setEnabled(((CheckBoxPreference) p).isChecked());
                findPreference("add_shortcut").setEnabled(((CheckBoxPreference) p).isChecked());
                findPreference("force_new_window").setEnabled(((CheckBoxPreference) p).isChecked());

                break;
            case "freeform_mode_help":
                AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                builder2.setView(View.inflate(getActivity(), R.layout.freeform_help_dialog, null))
                        .setTitle(R.string.freeform_help_dialog_title)
                        .setPositiveButton(R.string.action_close, null);

                AlertDialog dialog2 = builder2.create();
                dialog2.show();
                break;
            case "blacklist":
                Intent intent = null;

                switch(pref.getString("theme", "light")) {
                    case "light":
                        intent = new Intent(getActivity(), SelectAppActivity.class);
                        break;
                    case "dark":
                        intent = new Intent(getActivity(), SelectAppActivityDark.class);
                        break;
                }

                startActivity(intent);
                break;
            case "donate":
                NumberFormat format = NumberFormat.getCurrencyInstance();
                format.setCurrency(Currency.getInstance(Locale.US));

                AlertDialog.Builder builder3 = new AlertDialog.Builder(getActivity());
                builder3.setTitle(R.string.pref_title_donate)
                        .setMessage(getString(R.string.dialog_donate_message, format.format(1.99)))
                        .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=" + BuildConfig.BASE_APPLICATION_ID + ".paid"));
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                                try {
                                    startActivity(intent);
                                } catch (ActivityNotFoundException e) { /* Gracefully fail */ }
                            }
                        })
                        .setNegativeButton(R.string.action_no_thanks, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                noThanksCount++;

                                if(noThanksCount == 3) {
                                    pref.edit().putBoolean("hide_donate", true).apply();
                                    findPreference("donate").setEnabled(false);
                                }
                            }
                        });

                AlertDialog dialog3 = builder3.create();
                dialog3.show();
                break;
            case "pref_screen_general":
                getFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new GeneralFragment(), "GeneralFragment")
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .commit();
                break;
            case "pref_screen_appearance":
                getFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new AppearanceFragment(), "AppearanceFragment")
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .commit();
                break;
            case "pref_screen_recent_apps":
                getFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new RecentAppsFragment(), "RecentAppsFragment")
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .commit();
                break;
            case "pref_screen_freeform":
                getFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new FreeformModeFragment(), "FreeformModeFragment")
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .commit();
                break;
            case "pref_screen_advanced":
                getFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new AdvancedFragment(), "AdvancedFragment")
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .commit();
                break;
            case "icon_pack_list":
                Intent intent2 = null;

                switch(pref.getString("theme", "light")) {
                    case "light":
                        intent2 = new Intent(getActivity(), IconPackActivity.class);
                        break;
                    case "dark":
                        intent2 = new Intent(getActivity(), IconPackActivityDark.class);
                        break;
                }

                startActivityForResult(intent2, 123);
                break;
            case "add_shortcut":
                Intent intent3 = U.getShortcutIntent(getActivity());
                intent3.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
                intent3.putExtra("duplicate", false);

                getActivity().sendBroadcast(intent3);

                U.showToast(getActivity(), R.string.shortcut_created);
                break;
            case "notification_settings":
                Intent intent4 = new Intent();
                intent4.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent4.putExtra("app_package", BuildConfig.APPLICATION_ID);
                intent4.putExtra("app_uid", getActivity().getApplicationInfo().uid);

                try {
                    startActivity(intent4);
                    restartNotificationService = true;
                } catch (ActivityNotFoundException e) { /* Gracefully fail */ }
                break;
            case "dashboard_grid_size":
                AlertDialog.Builder builder4 = new AlertDialog.Builder(getActivity());
                LinearLayout dialogLayout = (LinearLayout) View.inflate(getActivity(), R.layout.dashboard_size_dialog, null);

                boolean isPortrait = getActivity().getApplicationContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                boolean isLandscape = getActivity().getApplicationContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

                int editTextId = -1;
                int editText2Id = -1;

                if(isPortrait) {
                    editTextId = R.id.fragmentEditText2;
                    editText2Id = R.id.fragmentEditText1;
                }

                if(isLandscape) {
                    editTextId = R.id.fragmentEditText1;
                    editText2Id = R.id.fragmentEditText2;
                }

                final EditText editText = (EditText) dialogLayout.findViewById(editTextId);
                final EditText editText2 = (EditText) dialogLayout.findViewById(editText2Id);

                builder4.setView(dialogLayout)
                        .setTitle(R.string.dashboard_grid_size)
                        .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                int width = Integer.parseInt(editText.getText().toString());
                                int height = Integer.parseInt(editText2.getText().toString());

                                if(width > 0 && height > 0) {
                                    SharedPreferences.Editor editor = pref.edit();
                                    editor.putInt("dashboard_width", width);
                                    editor.putInt("dashboard_height", height);
                                    editor.apply();

                                    updateDashboardGridSize(true);
                                }
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, null);

                editText.setText(Integer.toString(pref.getInt("dashboard_width", getActivity().getApplicationContext().getResources().getInteger(R.integer.dashboard_width))));
                editText2.setText(Integer.toString(pref.getInt("dashboard_height", getActivity().getApplicationContext().getResources().getInteger(R.integer.dashboard_height))));

                AlertDialog dialog4 = builder4.create();
                dialog4.show();

                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(editText2, InputMethodManager.SHOW_IMPLICIT);
                    }
                });

                break;
            case "reset_colors":
                AlertDialog.Builder builder5 = new AlertDialog.Builder(getActivity());
                builder5.setTitle(R.string.reset_colors)
                        .setMessage(R.string.are_you_sure)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                finishedLoadingPrefs = false;

                                AmbilWarnaPreference backgroundTintPref = (AmbilWarnaPreference) findPreference("background_tint");
                                backgroundTintPref.forceSetValue(getResources().getInteger(R.integer.translucent_gray));

                                AmbilWarnaPreference accentColorPref = (AmbilWarnaPreference) findPreference("accent_color");
                                accentColorPref.forceSetValue(getResources().getInteger(R.integer.translucent_white));

                                finishedLoadingPrefs = true;
                                restartTaskbar();
                            }
                        });

                AlertDialog dialog5 = builder5.create();
                dialog5.show();
                break;
            case "max_num_of_recents":
                final int max = 26;

                AlertDialog.Builder builder6 = new AlertDialog.Builder(getActivity());
                LinearLayout dialogLayout2 = (LinearLayout) View.inflate(getActivity(), R.layout.seekbar_pref, null);

                String value = pref.getString("max_num_of_recents", "10");

                final TextView textView = (TextView) dialogLayout2.findViewById(R.id.seekbar_value);
                textView.setText("0");

                final SeekBar seekBar = (SeekBar) dialogLayout2.findViewById(R.id.seekbar);
                seekBar.setMax(max);
                seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if(progress == max)
                            textView.setText(R.string.infinity);
                        else
                            textView.setText(Integer.toString(progress));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });

                seekBar.setProgress(Integer.parseInt(value));

                TextView blurb = (TextView) dialogLayout2.findViewById(R.id.blurb);
                blurb.setText(R.string.num_of_recents_blurb);

                builder6.setView(dialogLayout2)
                        .setTitle(R.string.pref_max_num_of_recents)
                        .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                int progress = seekBar.getProgress();
                                if(progress == max)
                                    progress = Integer.MAX_VALUE;

                                pref.edit().putString("max_num_of_recents", Integer.toString(progress)).apply();
                                updateMaxNumOfRecents(true);
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, null);

                AlertDialog dialog6 = builder6.create();
                dialog6.show();
                break;
            case "refresh_frequency":
                final int max2 = 20;

                AlertDialog.Builder builder7 = new AlertDialog.Builder(getActivity());
                LinearLayout dialogLayout3 = (LinearLayout) View.inflate(getActivity(), R.layout.seekbar_pref, null);

                String value2 = pref.getString("refresh_frequency", "2");

                final TextView textView2 = (TextView) dialogLayout3.findViewById(R.id.seekbar_value);
                textView2.setText(R.string.infinity);

                final SeekBar seekBar2 = (SeekBar) dialogLayout3.findViewById(R.id.seekbar);
                seekBar2.setMax(max2);
                seekBar2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if(progress == 0)
                            textView2.setText(R.string.infinity);
                        else
                            textView2.setText(Double.toString(progress * 0.5));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });

                seekBar2.setProgress((int) (Double.parseDouble(value2) * 2));

                TextView blurb2 = (TextView) dialogLayout3.findViewById(R.id.blurb);
                blurb2.setText(R.string.refresh_frequency_blurb);

                builder7.setView(dialogLayout3)
                        .setTitle(R.string.pref_title_recents_refresh_interval)
                        .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                double progress = seekBar2.getProgress() * 0.5;

                                pref.edit().putString("refresh_frequency", Double.toString(progress)).apply();
                                updateRefreshFrequency(true);
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, null);

                AlertDialog dialog7 = builder7.create();
                dialog7.show();
                break;
        }

        return true;
    }

    private void startTaskbarService(boolean fullRestart) {
        getActivity().startService(new Intent(getActivity(), TaskbarService.class));
        getActivity().startService(new Intent(getActivity(), StartMenuService.class));
        getActivity().startService(new Intent(getActivity(), DashboardService.class));
        if(fullRestart) getActivity().startService(new Intent(getActivity(), NotificationService.class));
    }

    private void stopTaskbarService(boolean fullRestart) {
        getActivity().stopService(new Intent(getActivity(), TaskbarService.class));
        getActivity().stopService(new Intent(getActivity(), StartMenuService.class));
        getActivity().stopService(new Intent(getActivity(), DashboardService.class));
        if(fullRestart) getActivity().stopService(new Intent(getActivity(), NotificationService.class));
    }

    private void restartTaskbar() {
        SharedPreferences pref = U.getSharedPreferences(getActivity());
        if(pref.getBoolean("taskbar_active", false) && !pref.getBoolean("is_hidden", false)) {
            pref.edit().putBoolean("is_restarting", true).apply();

            stopTaskbarService(true);
            startTaskbarService(true);
        } else if(U.isServiceRunning(getActivity(), StartMenuService.class)) {
            stopTaskbarService(false);
            startTaskbarService(false);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(getActivity());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == 123 && resultCode == Activity.RESULT_OK) {
            U.refreshPinnedIcons(getActivity());
            restartTaskbar();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if(restartNotificationService) {
            restartNotificationService = false;

            if(U.isServiceRunning(getActivity(), NotificationService.class)) {
                SharedPreferences pref = U.getSharedPreferences(getActivity());
                pref.edit().putBoolean("is_restarting", true).apply();

                Intent intent = new Intent(getActivity(), NotificationService.class);
                getActivity().stopService(intent);
                getActivity().startService(intent);
            }
        }
    }

    protected void updateDashboardGridSize(boolean restartTaskbar) {
        SharedPreferences pref = U.getSharedPreferences(getActivity());
        int width = pref.getInt("dashboard_width", getActivity().getApplicationContext().getResources().getInteger(R.integer.dashboard_width));
        int height = pref.getInt("dashboard_height", getActivity().getApplicationContext().getResources().getInteger(R.integer.dashboard_height));

        boolean isPortrait = getActivity().getApplicationContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean isLandscape = getActivity().getApplicationContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        int first = -1;
        int second = -1;

        if(isPortrait) {
            first = height;
            second = width;
        }

        if(isLandscape) {
            first = width;
            second = height;
        }

        findPreference("dashboard_grid_size").setSummary(getString(R.string.dashboard_grid_description, first, second));

        if(restartTaskbar) restartTaskbar();
    }

    protected void updateMaxNumOfRecents(boolean restartTaskbar) {
        SharedPreferences pref = U.getSharedPreferences(getActivity());
        int value = Integer.parseInt(pref.getString("max_num_of_recents", "10"));

        switch(value) {
            case 1:
                findPreference("max_num_of_recents").setSummary(R.string.max_num_of_recents_singular);
                break;
            case Integer.MAX_VALUE:
                findPreference("max_num_of_recents").setSummary(R.string.max_num_of_recents_unlimited);
                break;
            default:
                findPreference("max_num_of_recents").setSummary(getString(R.string.max_num_of_recents, value));
                break;
        }

        if(restartTaskbar) restartTaskbar();
    }

    protected void updateRefreshFrequency(boolean restartTaskbar) {
        SharedPreferences pref = U.getSharedPreferences(getActivity());
        String value = pref.getString("refresh_frequency", "2");
        double doubleValue = Double.parseDouble(value);
        int intValue = (int) doubleValue;

        if(doubleValue == 0)
            findPreference("refresh_frequency").setSummary(R.string.refresh_frequency_continuous);
        else if(doubleValue == 1)
            findPreference("refresh_frequency").setSummary(R.string.refresh_frequency_singular);
        else if(doubleValue == (double) intValue)
            findPreference("refresh_frequency").setSummary(getString(R.string.refresh_frequency, Integer.toString(intValue)));
        else
            findPreference("refresh_frequency").setSummary(getString(R.string.refresh_frequency, value));

        if(restartTaskbar) restartTaskbar();
    }
}