package org.onebillion.onecourse.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Display;
import android.view.Window;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.TimePicker;
import android.widget.Toast;

import org.onebillion.onecourse.controls.OBLabel;
import org.onebillion.onecourse.mainui.MainActivity;
import org.onebillion.onecourse.receivers.OBBatteryReceiver;
import org.onebillion.onecourse.receivers.OBDeviceAdminReceiver;
import org.onebillion.onecourse.receivers.OBSettingsContentObserver;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by pedroloureiro on 31/08/16.
 */
public class OBSystemsManager implements TimePickerDialog.OnTimeSetListener, DatePickerDialog.OnDateSetListener
{
    public static OBSystemsManager sharedManager;

    private OBBatteryReceiver batteryReceiver;
    private OBBrightnessManager brightnessManager;
    private OBExpansionManager expansionManager;
    private Map<String, List<String>> memoryUsageMap;

    private Handler mainHandler;
    public OBLabel statusLabel;

    public OBSettingsContentObserver settingsContentObserver;
    public OBConnectionManager connectionManager;

    private ReentrantLock backupLock;
    private boolean AppIsInForeground;
    private boolean suspended;
    private boolean kioskModeActive = false;

    private OBUtils.RunLambda dateSetCompletionBlock, timeSetCompletionBlock;


    public OBSystemsManager (Activity activity)
    {
        batteryReceiver = new OBBatteryReceiver();
        //
        settingsContentObserver = new OBSettingsContentObserver(activity, new Handler());
        //
        brightnessManager = new OBBrightnessManager();
        expansionManager = new OBExpansionManager();
        connectionManager = new OBConnectionManager();
        //
        suspended = true;
        //
        memoryUsageMap = new HashMap<String, List<String>>();
        //
        sharedManager = this;
    }

    public void checkForConnectivity (final OBUtils.RunLambda block)
    {
        if (shouldConnectToWifiOnStartup() && this.connectionManager.wifiSSID() != null && this.connectionManager.wifiPassword() != null)
        {
            this.connectionManager.startupConnection(block);
        }
        else
        {
            if (block != null)
            {
                try
                {
                    MainActivity.log("OBSystemsManager.checkForConnectivity. running completion block");
                    OBUtils.runOnOtherThreadDelayed(1.0f, new OBUtils.RunLambda()
                    {
                        @Override
                        public void run () throws Exception
                        {
                            block.run();
                        }
                    });
                }
                catch (Exception e)
                {
                    MainActivity.log("OBSystemsManager.checkForConnectivity.exception caught while running completion block");
//                    e.printStackTrace();
                }
            }
        }
    }

    public boolean hasWriteSettingsPermission ()
    {
        if (!MainActivity.isSDKCompatible())
        {
            MainActivity.log("OBSystemsManager:hasWriteSettingsPermission: incompatible SDK version. exiting function");
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            return Settings.System.canWrite(MainActivity.mainActivity);
        }
        else
        {
            MainActivity.log("OBSystemsManager:hasWriteSettingsPermission: failsafe incompatible SDK version. exiting function");
            return true;
        }
    }

    public boolean isAppIsInForeground ()
    {
        return AppIsInForeground;
    }


    public void setDateTime ()
    {

    }


    public boolean isScreenOn (Context context)
    {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH)
        {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            boolean screenOn = false;
            for (Display display : dm.getDisplays())
            {
                if (display.getState() != Display.STATE_OFF)
                {
                    screenOn = true;
                }
            }
            return screenOn;
        }
        else
        {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            //noinspection deprecation
            return pm.isScreenOn();
        }
    }


    private boolean isMyServiceRunning (Class<?> serviceClass)
    {
        ActivityManager manager = (ActivityManager) MainActivity.mainActivity.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
        {
            if (serviceClass.getName().equals(service.service.getClassName()))
            {
                return true;
            }
        }
        return false;
    }


    public void killAllServices ()
    {
        if (suspended) return;
        if (!OBSystemsManager.isFirstSetupComplete()) return;
        //
        MainActivity.log("OBSystemsManager.killing all services");
        //
        MainActivity.mainActivity.stopService(new Intent(MainActivity.mainActivity, OBAutoStartActivityService.class));
    }


    public void startServices ()
    {
        if (suspended) return;
        if (!OBSystemsManager.isFirstSetupComplete()) return;
        //
        MainActivity.log("OBSystemsManager.startServices");
        //
        String restartAfterCrash = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_RESTART_AFTER_CRASH);
        boolean shouldRestartAfterCrash = restartAfterCrash != null && restartAfterCrash.equals("true");
        //
        if (!isMyServiceRunning(OBAutoStartActivityService.class) && shouldRestartAfterCrash)
        {
            MainActivity.log("OBAutoStartActivityService was not running. Starting now");
            //
            Intent serviceIntent = new Intent(MainActivity.mainActivity, OBAutoStartActivityService.class);
            MainActivity.mainActivity.startService(serviceIntent);
        }
        MainActivity.log("OBSystemsManager.startServices complete");
    }


    public static boolean isFirstSetupComplete ()
    {
        return OBPreferenceManager.getBooleanPreference("firstSetupComplete");
//        String result = MainActivity.mainActivity.getPreferences("firstSetupComplete");
//        return result != null && result.equals("true");
    }


    public void runChecks ()
    {
        MainActivity.log("OBSystemsManager.runChecks check for suspended");
        if (suspended) return;
        //
        MainActivity.log("OBSystemsManager.runChecks check for first setup complete");
        if (!OBSystemsManager.isFirstSetupComplete()) return;
        //
        MainActivity.log("OBSystemsManager.runChecks");
        //
        MainActivity.log("OBSystemsManager.runChecks --> disableNavigationBar");
        disableNavigationBar(); // may cause restart
        //
        MainActivity.log("OBSystemsManager.runChecks --> pinApplication");
        pinApplication();
        //
        MainActivity.log("OBSystemsManager.runChecks --> startServices");
        //
        startServices();
        //
        if (mainHandler == null)
        {
            mainHandler = new Handler(MainActivity.mainActivity.getMainLooper());
        }
        //
//        if (shouldConnectToWifiOnStartup())
//        {
//            MainActivity.log("OBSystemsManager.runChecks --> startupConnection");
//            connectionManager.sharedManager.startupConnection(null);
//        }
        //
        MainActivity.log("OBSystemsManager.runChecks --> SQL maintenance");
        OBSQLiteHelper.getSqlHelper().runMaintenance();
        //
        if (shouldRunChecksumVerification())
        {
            OBUtils.runOnOtherThread(new OBUtils.RunLambda()
            {
                @Override
                public void run () throws Exception
                {
                    MainActivity.log("OBSystemsManager.runChecks --> checksum comparison");
                    runChecksumComparisonTest();
                }
            });
        }
    }


    public Handler getMainHandler ()
    {
        if (mainHandler == null)
        {
            mainHandler = new Handler(MainActivity.mainActivity.getMainLooper());
        }
        return mainHandler;
    }


    public void printMemoryStatus (String message)
    {
        if (!MainActivity.isSDKCompatible())
        {
            MainActivity.log("OBSystemsManager:printMemoryStatus: incompatible SDK version. exiting function");
            return;
        }
        //
        ActivityManager activityManager = (ActivityManager) MainActivity.mainActivity.getSystemService(MainActivity.ACTIVITY_SERVICE);
        int id = android.os.Process.myPid();
        int[] list = {id};
        Debug.MemoryInfo result[] = activityManager.getProcessMemoryInfo(list);

        if (message != null) MainActivity.log("Memory status: " + message);

        for (Debug.MemoryInfo info : result)
        {
            Map<String, String> map = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            {
                map = info.getMemoryStats();
            }
            for (String key : map.keySet())
            {
                List<String> memoryEntry = memoryUsageMap.get(key);
                if (memoryEntry == null) memoryEntry = new ArrayList<String>();
                memoryEntry.add(map.get(key));
                memoryUsageMap.put(key, memoryEntry);
            }
        }
        //
        for (String key : memoryUsageMap.keySet())
        {
            List<String> values = memoryUsageMap.get(key);
            if (values == null) continue;
            if (values.isEmpty()) continue;
            //
            long firstValue = Long.parseLong(values.get(0));
            long lastValue = Long.parseLong(values.get(values.size() - 1));
            if (values.size() > 2)
            {
                long secondLastValue = Long.parseLong(values.get(values.size() - 2));
                long diff = lastValue - secondLastValue;
                long fullDiff = lastValue - firstValue;
                MainActivity.log("Memory status: " + key + " --> " + lastValue + " (" + ((diff > 0) ? "+" : "") + diff + ")  --> since beginning (" + ((fullDiff > 0) ? "+" : "") + fullDiff + ")");
            }
            else
            {
                long diff = lastValue - firstValue;
                MainActivity.log("Memory status: " + key + " --> " + lastValue + " (" + ((diff > 0) ? "+" : "") + diff + ")");
            }
        }
    }


    public void runBatterySavingMode ()
    {
        MainActivity.log("OBSystemsManager.runBatterySavingMode");
        OBConnectionManager.sharedManager.disconnectWifiIfAllowed();
        OBConnectionManager.sharedManager.setBluetooth(false);
        //
        //killBackgroundProcesses(); it's killing the keep alive services
        MainActivity.log("OBSystemsManager.runBatterySavingMode complete");
    }


    public void killBackgroundProcesses ()
    {
        MainActivity.log("OBSystemsManager.killBackgroundProcesses");
        ActivityManager activityManager = (ActivityManager) MainActivity.mainActivity.getSystemService(MainActivity.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procInfo = activityManager.getRunningAppProcesses();
        if (procInfo != null)
        {
            for (ActivityManager.RunningAppProcessInfo process : procInfo)
            {
                int importance = process.importance;
                int pid = process.pid;
                String name = process.processName;
                //
                if (name.equals("manager.main")) continue;
                if (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
                {
                    continue;
                }
                MainActivity.log("OBSystemsManager.killBackgroundProcesses: " + name);
                activityManager.killBackgroundProcesses(name);
            }
        }
    }


    public void onResume ()
    {
        MainActivity.log("OBSystemsManager.onResume detected");
        //
        AppIsInForeground = true;
        //
        if (batteryReceiver != null)
        {
            registerBatteryReceiver();
        }
        //
        if (settingsContentObserver != null)
        {
            settingsContentObserver.onResume();
        }
        if (expansionManager != null)
        {
            MainActivity.mainActivity.registerReceiver(OBExpansionManager.sharedManager.downloadCompleteReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
        //
        OBBrightnessManager.sharedManager.onResume();
    }


    public void onPause ()
    {
        MainActivity.log("OBSystemsManager.onPause detected" + (MainActivity.mainActivity.isFinishing() ? " and is finishing!" : ""));
        //
        AppIsInForeground = false;
        //
//        startForegroundCheck();
        //
        if (batteryReceiver != null)
        {
            MainActivity.mainActivity.unregisterReceiver(batteryReceiver);
        }
        //
        if (settingsContentObserver != null)
        {
            settingsContentObserver.onPause();
        }
        if (OBExpansionManager.sharedManager.downloadCompleteReceiver != null)
        {
            MainActivity.mainActivity.unregisterReceiver(OBExpansionManager.sharedManager.downloadCompleteReceiver);
        }
        //
        OBBrightnessManager.sharedManager.onPause();
        //
//        OBSQLiteHelper.getSqlHelper().emergencyRestore();
    }


    public void onDestroy ()
    {
        MainActivity.log("OBSystemsManager.onDestroy detected");
    }


    public void onStop ()
    {
        if (MainActivity.mainActivity.isFinishing())
        {
            MainActivity.log("OBSystemsManager.onStop detected");
            //
            shutdownProcedures();
            //
            OBBrightnessManager.sharedManager.onStop();
        }
    }


    public void onSuspend ()
    {
        MainActivity.log("OBSystemsManager.onSuspend detected");
        //
        suspended = true;
        //
        killAllServices();
        //
        OBBrightnessManager.sharedManager.onSuspend();
    }


    public void onContinue ()
    {
        MainActivity.log("OBSystemsManager.onContinue");
        //
        suspended = false;
        //
        OBBrightnessManager.sharedManager.onContinue();
        //
        startServices();
        //
        runBatterySavingMode();
        //
        MainActivity.log("OBSystemsManager.onContinue complete");
    }


    public void refreshStatus ()
    {
        String info = String.format("Battery [%s]    Brightness[%s]    Volume[%s]", batteryReceiver.printStatus(), brightnessManager.printStatus(), settingsContentObserver.printVolumeStatus());
        //
        if (statusLabel != null)
        {
            statusLabel.setString(info);
        }
    }


    public void setStatusLabel (OBLabel label)
    {
        statusLabel = label;
        refreshStatus();
    }

    public void registerBatteryReceiver()
    {
        Intent intent = MainActivity.mainActivity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        MainActivity.mainActivity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_LOW));
        MainActivity.mainActivity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_OKAY));
        MainActivity.mainActivity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        MainActivity.mainActivity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
       // batteryReceiver.onReceive(null,intent);
    }



    private void runChecksumComparisonTest ()
    {
        MainActivity.log("Checksum comparison BEGIN");
        OBXMLManager xmlManager = new OBXMLManager();
        List<File> checkSumFiles = OBExpansionManager.sharedManager.getChecksumFiles();
        for (File file : checkSumFiles)
        {
            try
            {
                List<OBXMLNode> xml = xmlManager.parseFile(new FileInputStream(file));
                OBXMLNode rootNode = xml.get(0);
                //
                for (OBXMLNode node : rootNode.children)
                {
                    String path = node.attributeStringValue("id");
                    String md5 = node.attributeStringValue("md5");
                    InputStream is = OBUtils.getInputStreamForPath(path);
                    Boolean result = checkMD5(md5, is);
                    if (!result)
                    {
                        MainActivity.log("Checksum comparison: Problem detected with asset " + path);
                    }
//                    MainActivity.log("CHECKSUM COMPARISON --> " + md5 + "\t" + ((is == null) ? "NOT FOUND" : "FOUND" + "\t" + (result ? "OK" : "ERROR") + "\t" + path));
                }
            }
            catch (Exception e)
            {
                MainActivity.log("Exception caught while running the checksum comparison test");
//                e.printStackTrace();
            }
        }
        MainActivity.log("Checksum comparison END");
    }


    public static boolean checkMD5 (String md5, InputStream is)
    {
        if (TextUtils.isEmpty(md5) || is == null)
        {
            MainActivity.log("MD5 string empty or inputStream null");
            return false;
        }
        //
        String calculatedDigest = calculateMD5(is);
        if (calculatedDigest == null)
        {
            MainActivity.log("calculatedDigest null");
            return false;
        }
//        MainActivity.log("Calculated digest: " + calculatedDigest);
//        MainActivity.log("Provided digest: " + md5);
        //
        return calculatedDigest.equalsIgnoreCase(md5);
    }


    public static String calculateMD5 (InputStream is)
    {
        MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException e)
        {
            MainActivity.log("Exception while getting digest");
//            e.printStackTrace();
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try
        {
            while ((read = is.read(buffer)) > 0)
            {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to process file for MD5", e);
        }
        finally
        {
            try
            {
                is.close();
            }
            catch (IOException e)
            {
                MainActivity.log("Exception on closing MD5 input stream");
//                e.printStackTrace();
            }
        }
    }


    public void requestRootAccess ()
    {
        MainActivity.log("OBSystemsManager.requestRootAccess");
        Process p;
        try
        {
            p = Runtime.getRuntime().exec("su");
            //
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("echo \"Do I have root?\" >/system/sd/temporary.txt\n");
            os.writeBytes("exit\n");
            os.flush();
            //
            try
            {
                p.waitFor();
                if (p.exitValue() != 255)
                {
                    MainActivity.log("OBSystemsManager.requestRootAccess --> App has been granted Root Access");
                }
                else
                {
                    MainActivity.log("OBSystemsManager.requestRootAccess --> App has NOT been granted Root Access");
                }
            }
            catch (InterruptedException e)
            {
                MainActivity.log("OBSystemsManager.requestRootAccess --> App has NOT been granted Root Access");
            }
        }
        catch (IOException e)
        {
            MainActivity.log("OBSystemsManager.requestRootAccess --> App has NOT been granted Root Access");
        }
    }


    public boolean hasAdministratorPrivileges ()
    {
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return devicePolicyManager.isAdminActive(AdministratorReceiver());
    }


    public ComponentName AdministratorReceiver ()
    {
        return OBDeviceAdminReceiver.getComponentName(MainActivity.mainActivity);
    }


    public boolean isDeviceOwner ()
    {
        MainActivity.log("OBSystemsManager.isDeviceOwner");
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean result = devicePolicyManager.isDeviceOwnerApp(MainActivity.mainActivity.getPackageName());
        MainActivity.log(result ? "It is device owner" : "It's NOT device owner");
        return result;
    }


    public void requestToRemoveAccounts ()
    {
        Toast.makeText(MainActivity.mainActivity, "Please remove all accounts before going back", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);
        MainActivity.mainActivity.startActivityForResult(intent, MainActivity.REQUEST_FIRST_SETUP_ADMINISTRATOR_PRIVILEGES);
    }


    public void requestDeviceOwner ()
    {
        MainActivity.log("OBSystemsManager.requestDeviceOwner");
        //
        String packageName = MainActivity.mainActivity.getPackageName();
        try
        {
//            String[] command = new String[]{"su", "-c", "dpm set-device-owner " + packageName + "/" + AdministratorReceiver().getClassName()}; // for normal Root
            String[] command = new String[]{"/system/xbin/su", "1000", "dpm", "set-device-owner", packageName + "/" + AdministratorReceiver().getClassName()}; // for AOSP root
            //
            MainActivity.log("OBSystemsManager.requestDeviceOwner.running [" + Arrays.toString(command) + "]");
            //
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
            //
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null)
            {
                log.append(line + "\n");
            }
            bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = bufferedReader.readLine()) != null)
            {
                log.append(line + "\n");
            }
            String output = log.toString();
            MainActivity.log("OBSystemsManager.requestDeviceOwner.output from process: " + output);
            //
            if (!output.contains("Active admin set to component"))
            {
                Toast.makeText(MainActivity.mainActivity, "Device Owner was not set. Another App is the device owner.", Toast.LENGTH_LONG).show();
            }
        }
        catch (Exception e)
        {
            MainActivity.log("OBSystemsManager.requestDeviceOwner: device is not rooted. No point in continuing.");
//            e.printStackTrace();
        }
    }


    public boolean enableAdminstratorPrivileges ()
    {
        MainActivity.log("OBSystemsManager.enableAdminstratorPrivileges");
        //
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminReceiver = OBDeviceAdminReceiver.getComponentName(MainActivity.mainActivity);
        //
        if (!devicePolicyManager.isAdminActive(adminReceiver))
        {
            MainActivity.log("OBSystemsManager.enableAdminstratorPrivileges.requesting administrator privileges");
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminReceiver);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "");
            MainActivity.mainActivity.startActivityForResult(intent, MainActivity.REQUEST_FIRST_SETUP_ADMINISTRATOR_PRIVILEGES);
            return false;
        }
        if (!devicePolicyManager.isDeviceOwnerApp(MainActivity.mainActivity.getPackageName()))
        {
            MainActivity.log("OBSystemsManager.enableAdminstratorPrivileges.requesting device owner");
            String packageName = MainActivity.mainActivity.getPackageName();
            try
            {
                // String resetCommand = "su -c rm /data/system/device_owner.xml; su -c rm /data/system/device_policies.xml;";
                //
                String[] command = new String[]{"su", "-c", "dpm set-device-owner " + packageName + "/" + adminReceiver.getClassName()};
                //
                MainActivity.log("OBSystemsManager.enableAdministratorPrivileges.running [" + command.toString() + "]");
                //
                Process process = Runtime.getRuntime().exec(command);
                process.waitFor();
                //
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder log = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null)
                {
                    log.append(line + "\n");
                }
                bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((line = bufferedReader.readLine()) != null)
                {
                    log.append(line + "\n");
                }
                String output = log.toString();
                MainActivity.log("OBSystemsManager.output from process: " + output);
                //
                if (output.contains("Active admin set to component"))
                {
                    MainActivity.log("OBSystemsManager. App is now set to device owner");
                    return true;
                }
                else
                {
                    MainActivity.log("OBSystemsManager. Another account was detected. Requesting user to remove them"); // no idea how to do this programmatically
                    //
                    Toast.makeText(MainActivity.mainActivity, "Please remove all accounts before going back", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);
                    MainActivity.mainActivity.startActivityForResult(intent, MainActivity.REQUEST_FIRST_SETUP_ADMINISTRATOR_PRIVILEGES);
                    //
                    return false;
                }
            }
            catch (Exception e)
            {
                MainActivity.log("OBSystemsManager.device is not rooted. No point in continuing enableAdminstratorPrivileges");
//                e.printStackTrace();
                //
                return true;
            }
        }
        MainActivity.log("OBSystemsManager.enableAdminstratorPrivileges. all good");
        return true;
    }


    public void disableAdministratorPrivileges ()
    {
        if (!MainActivity.isSDKCompatible())
        {
            MainActivity.log("OBSystemsManager:disableAdministratorPrivileges: incompatible SDK version. exiting function");
            return;
        }
        MainActivity.log("OBSystemsManager.disableAdministratorPrivileges");
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminReceiver = OBDeviceAdminReceiver.getComponentName(MainActivity.mainActivity);
        //
        if (devicePolicyManager.isDeviceOwnerApp(MainActivity.mainActivity.getPackageName()))
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                devicePolicyManager.setKeyguardDisabled(adminReceiver, false);
            }
            MainActivity.log("OBSystemsManager.keyguard restored");
            //
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                devicePolicyManager.setStatusBarDisabled(adminReceiver, false);
            }
            MainActivity.log("OBSystemsManager.status bar restored");
            //
            try
            {
                MainActivity.log("OBSystemsManager.disableAdministratorPrivileges: removing active admin");
                devicePolicyManager.removeActiveAdmin(adminReceiver);
                MainActivity.log("OBSystemsManager.disableAdministratorPrivileges: clearing device owner");
                devicePolicyManager.clearDeviceOwnerApp(MainActivity.mainActivity.getPackageName());
                MainActivity.log("OBSystemsManager.disableAdministratorPrivileges: done");
            }
            catch (Exception e)
            {
                MainActivity.log("OBSystemsManager.disableAdministratorPrivileges: exception caught");
//                e.printStackTrace();
                // App might not be the device owner at this point
            }
        }
        if (kioskModeActive)
        {
            MainActivity.mainActivity.stopLockTask();
            kioskModeActive = false;
        }
    }


    public void pinApplication ()
    {
        MainActivity.log("OBSystemsManager.pinApplication");
        //
        if (shouldPinApplication())
        {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminReceiver = OBDeviceAdminReceiver.getComponentName(MainActivity.mainActivity);
            //
            if (devicePolicyManager.isDeviceOwnerApp(MainActivity.mainActivity.getPackageName()))
            {
                MainActivity.log("OBSystemsManager.pinApplication: attempting to pin app");
                String[] packages = {MainActivity.mainActivity.getPackageName()};
                devicePolicyManager.setLockTaskPackages(adminReceiver, packages);
                //
                if (devicePolicyManager.isLockTaskPermitted(MainActivity.mainActivity.getPackageName()))
                {
                    if (isAppIsInForeground())
                    {
                        try
                        {
                            MainActivity.log("OBSystemsManager.pinApplication: starting locked task");
                            MainActivity.mainActivity.startLockTask();
                            kioskModeActive = true;
                        }
                        catch (Exception e)
                        {
                            MainActivity.log("OBSystemsManager.pinApplication: exception caught");
//                            e.printStackTrace();
                            kioskModeActive = false;
                        }
                    }
                    else
                    {
                        MainActivity.log("OBSystemsManager.pinApplication:application is not in foreground, cancelling");
                    }
                }
            }
            else
            {
                MainActivity.log("OBSystemsManager.pinApplication: unable to pin application, not a device owner");
            }
            toggleKeyguardAndStatusBar(false);
        }
        else
        {
            MainActivity.log("OBSystemsManager.pinApplication: disabled in settings");
        }
    }


    public void toggleKeyguardAndStatusBar (boolean status)
    {
        if (!MainActivity.isSDKCompatible())
        {
            MainActivity.log("OBSystemsManager:toggleKeyguardAndStatusBar: incompatible SDK version. exiting function");
            return;
        }
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminReceiver = OBDeviceAdminReceiver.getComponentName(MainActivity.mainActivity);
        //
        if (devicePolicyManager.isDeviceOwnerApp(MainActivity.mainActivity.getPackageName()))
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                devicePolicyManager.setKeyguardDisabled(adminReceiver, !status);
            }
            MainActivity.log("OBSystemsManager.keyguard has been " + (status ? "enabled" : "disabled"));
            //
//            devicePolicyManager.setStatusBarDisabled(adminReceiver, !status);
//            MainActivity.log("OBSystemsManager.status bar has been " + (status ? "enabled" : "disabled"));
        }
    }


    public void screenLock ()
    {
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) MainActivity.mainActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        devicePolicyManager.lockNow();
    }

    public void shutdownProcedures ()
    {
        // the shutdown procedure should only be triggered for debug mode, as we want the app to restart automatically and not lose any setting
        //
        MainActivity.log("OBSystemManager.shutdownProcedures");
        //
        OBBrightnessManager.sharedManager.disableBrightnessAdjustment();
        //
        saveLogToFile();
    }


    public void toggleNavigationBar (final int value)
    {
        MainActivity.log("OBSystemsManager." + (value == 0 ? "enabling" : "disabling") + " navigation bar");
        try
        {
            String command = "getprop qemu.hw.mainkeys";
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
            //
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null)
            {
                log.append(line + "\n");
            }
            bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = bufferedReader.readLine()) != null)
            {
                log.append(line + "\n");
            }
            String output = log.toString();
            MainActivity.log("OBSystemsManager.output from toggleNavigationBar: " + output);
            //
            if (output.contains(String.format("%d", value)))
            {
                MainActivity.log("OBSystemsManager.toggleNavigationBar. the system value is the same. nothing to do here");
                return;
            }
            command = "su -c 'setprop qemu.hw.mainkeys " + value + "; stop; start'";
            MainActivity.log("OBSystemsManager.running:" + command);
            //
            try
            {
                process = Runtime.getRuntime().exec(command);
                process.waitFor();
                //
                bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                log = new StringBuilder();
                while ((line = bufferedReader.readLine()) != null)
                {
                    log.append(line + "\n");
                }
                bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((line = bufferedReader.readLine()) != null)
                {
                    log.append(line + "\n");
                }
                output = log.toString();
                MainActivity.log("OBSystemsManager.toggleNavigationBar.output from process: " + output);
            }
            catch (Exception e)
            {
                MainActivity.log("OBSystemsManager.toggleNavigationBar. unable to execute command. Device is probably not rooted");
            }
        }
        catch (Exception e)
        {
            MainActivity.log("OBSystemsManager.toggleNagivationBar. exception caught");
//            e.printStackTrace();
        }

    }


    public void disableNavigationBar ()
    {
        String hideNavigationBar = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_HIDE_NAVIGATION_BAR);
        //
        if (hideNavigationBar != null && hideNavigationBar.equals("true"))
        {
            toggleNavigationBar(1);
        }
    }


    public void enableNavigationBar ()
    {
        toggleNavigationBar(0);
    }


    public void saveLogToFile ()
    {
        File sd = new File(Environment.getExternalStorageDirectory(), "//onebillion//logs//");
        sd.mkdirs();
        //
        if (sd.canWrite())
        {
            DateFormat df = new android.text.format.DateFormat();
            String date = df.format("yyyy.MM.dd.hh.mm.ss", new java.util.Date()).toString();
            File file = new File(sd, String.format("%s.txt", date));
            try
            {
                MainActivity.log("Log exported to " + file.getAbsolutePath());
                @SuppressWarnings("unused")
                Process process = Runtime.getRuntime().exec("logcat -df " + file.getAbsolutePath());
            }
            catch (Exception e)
            {
                MainActivity.log("OBSystemsManager.saveLogToFile: exception caught");
//                e.printStackTrace();
            }
        }
    }


    public boolean usesAdministratorServices ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_USE_ADMINISTRATOR_SERVICES);
        return (value != null && value.equalsIgnoreCase("true"));
    }

    public boolean shouldRequestDeviceOwner ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_REQUEST_DEVICE_OWNER);
        return (value != null && value.equalsIgnoreCase("true"));
    }


    public boolean shouldPinApplication ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_PIN_APPLICATION);
        return (value != null && value.equalsIgnoreCase("true"));
    }


    public boolean shouldShowDateTimeSettings ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_SHOW_DATE_TIME_SETTINGS);
        return (value != null && value.equalsIgnoreCase("true"));
    }


    public boolean shouldRunChecksumVerification ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_CHECKSUM_VERIFICATION);
        return (value != null && value.equalsIgnoreCase("true"));
    }

    public boolean shouldConnectToWifiOnStartup ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_WIFI_CONNECT_ON_STARTUP);
        return (value != null && value.equalsIgnoreCase("true"));
    }


    public boolean shouldSendBackupWhenConnected ()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_BACKUP_SEND_WHEN_CONNECTED);
        return (value != null && value.equalsIgnoreCase("true"));
    }

    public String backup_Wifi_SSID ()
    {
        return MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_BACKUP_WIFI);
    }

    public URL backup_URL () throws MalformedURLException
    {
        String url = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_BACKUP_URL);
        return new URL("http://" + url);
    }

    public int backup_interval () // MINUTES
    {
        String value_string = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_BACKUP_INTERVAL);
        if (value_string == null)
        {
            MainActivity.log("OBSystemsManager.backup_interval Please enter a value in settings.plist for " + MainActivity.CONFIG_BACKUP_INTERVAL + ". Using default 120mins");
            return 120;
        }
        int value = Integer.parseInt(value_string);
        return value;
    }

    public boolean backup_isRequired ()
    {
        MainActivity.log("OBSystemsManager.backup_isRequired");
        String value_string = OBPreferenceManager.getStringPreference("lastBackupTimeStamp");
//        String value_string = MainActivity.mainActivity.getPreferences("lastBackupTimeStamp");
        if (value_string == null) return true;
        long value = Long.parseLong(value_string);
        long currentTime = System.currentTimeMillis() / 1000;
        long elapsed = currentTime - value;
        boolean result = elapsed > backup_interval() * 60;
        MainActivity.log("OBSystemsManager.backup_isRequired --> " + result);
        return result;
    }

    public void backup_connectToWifiAndUploadDatabase ()
    {
        MainActivity.log("OBSystemsManager.backup_connectToWifiAndUploadDatabase");
        OBUtils.runOnOtherThread(new OBUtils.RunLambda()
        {
            @Override
            public void run () throws Exception
            {
                connectionManager.connectToNetwork_scanForWifi(backup_Wifi_SSID(), "", new OBUtils.RunLambda()
                {
                    @Override
                    public void run () throws Exception
                    {
                        backup_uploadDatabase();
                    }
                });
            }
        });
    }

    public Lock backup_getLock ()
    {
        if (backupLock == null)
        {
            backupLock = new ReentrantLock();
        }
        return backupLock;
    }

    public void backup_uploadDatabase ()
    {
        MainActivity.log("OBSystemsManager.backup_uploadDatabase attempting lock");
        if (backup_getLock().tryLock())
        {
            try
            {
                MainActivity.log("OBSystemsManager.backup_uploadDatabase.generating database backup");
                final boolean freshBackup = true;
                String fileURL = (freshBackup) ? OBSQLiteHelper.getSqlHelper().backupDatabase() : OBSQLiteHelper.getSqlHelper().getLatestDatabaseBackup();
                if (fileURL == null) fileURL = OBSQLiteHelper.getSqlHelper().backupDatabase();
                //
                if (fileURL == null)
                {
                    MainActivity.log("OBSystemsManager.backup_uploadDatabase.could not generate a database backup");
                    return;
                }
                //
                File file = new File(fileURL);
                URL url = backup_URL();
                //
                String attachmentName = "file";
                String attachmentFileName = "database.sql";
                String crlf = "\r\n";
                String twoHyphens = "--";
                String boundary = "*****";
                //
                HttpURLConnection httpUrlConnection = null;

                MainActivity.log("OBSystemsManager.backup_uploadDatabase.URL: " + url);
                httpUrlConnection = (HttpURLConnection) url.openConnection();
                httpUrlConnection.setUseCaches(false);
                httpUrlConnection.setDoOutput(true);
                //
                httpUrlConnection.setRequestMethod("POST");
                httpUrlConnection.setRequestProperty("Connection", "Keep-Alive");
                httpUrlConnection.setRequestProperty("Cache-Control", "no-cache");
                httpUrlConnection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
                //
                DataOutputStream request = new DataOutputStream(httpUrlConnection.getOutputStream());
                request.writeBytes(twoHyphens + boundary + crlf);
                request.writeBytes("Content-Disposition: form-data; name=\"" + attachmentName + "\";filename=\"" + attachmentFileName + "\"" + crlf);
                request.writeBytes(crlf);
                //
                int size = (int) file.length();
                byte[] bytes = new byte[size];
                BufferedInputStream buffer = new BufferedInputStream(new FileInputStream(file));
                buffer.read(bytes, 0, bytes.length);
                request.write(bytes);
                //
                request.writeBytes(crlf);
                request.writeBytes(twoHyphens + boundary + twoHyphens + crlf);
                request.flush();
                request.close();
                //
                InputStream responseStream = new BufferedInputStream(httpUrlConnection.getInputStream());
                BufferedReader responseStreamReader = new BufferedReader(new InputStreamReader(responseStream));
                String line = "";
                StringBuilder stringBuilder = new StringBuilder();

                while ((line = responseStreamReader.readLine()) != null)
                {
                    stringBuilder.append(line).append("\n");
                }
                responseStreamReader.close();
                //
                String response = stringBuilder.toString();
                //
                MainActivity.log("OBSystemsManager.backup_uploadDatabase response from server: " + response);
                //
                responseStream.close();
                httpUrlConnection.disconnect();
                //
                MainActivity.log("OBSystemsManager.backup_uploadDatabase updating lastBackupTimeStamp");
                long currentTime = System.currentTimeMillis() / 1000;
                OBPreferenceManager.setPreference("lastBackupTimeStamp", String.format("%d", currentTime));
//                MainActivity.mainActivity.addToPreferences("lastBackupTimeStamp", String.format("%d", currentTime));
                //
                MainActivity.log("OBSystemsManager.backup_uploadDatabase disconnecting from wifi");
                connectionManager.disconnectWifi();
                //
                OBUtils.runOnMainThread(new OBUtils.RunLambda()
                {
                    @Override
                    public void run () throws Exception
                    {
                        Toast.makeText(MainActivity.mainActivity, "Database has been uploaded to the server", Toast.LENGTH_LONG).show();
                    }
                });
            }
            catch (Exception e)
            {
                MainActivity.log("OBSystemsManager.backup_uploadDatabase exception caught");
//                e.printStackTrace();
            }
            MainActivity.log("OBSystemsManager.backup_uploadDatabase releasing lock");
            backup_getLock().unlock();
        }
        else
        {
            MainActivity.log("OBSystemsManager.backup_uploadDatabase backup already in progress");
        }
    }


    public static void unregisterReceiver (BroadcastReceiver receiver)
    {
        if (receiver != null)
        {
            try
            {
                MainActivity.mainActivity.unregisterReceiver(receiver);
                MainActivity.log("OBSystemsManager.unregisterReceiver successfull");
            }
            catch (Exception e)
            {
                MainActivity.log("OBSystemsManager.unregisterReceiver NOT successfull --> not registered");
            }
        }
        else
        {
            MainActivity.log("OBSystemsManager.unregisterReceiver NOT successfull --> not initialised");
        }
    }

    public Dialog createDateSetDialog (String message, Boolean cancelable, OBUtils.RunLambda completionBlock)
    {
        dateSetCompletionBlock = completionBlock;
        final Calendar calendar = Calendar.getInstance();
        DatePickerDialog d = new DatePickerDialog(MainActivity.mainActivity, this, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
        //
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        //
        if (message == null)
        {
            LinearLayout linearLayout = new LinearLayout(MainActivity.mainActivity.getApplicationContext());
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCustomTitle(linearLayout);
        }
        else
        {
            d.setMessage(message + "\n");
        }
        //
        d.setButton(DatePickerDialog.BUTTON_NEGATIVE, null, (DialogInterface.OnClickListener) null);
        //
        DatePicker datePicker = d.getDatePicker();
        calendar.clear();
        calendar.set(2016, Calendar.JANUARY, 1);
        datePicker.setMinDate(calendar.getTimeInMillis());
        calendar.clear();
        calendar.set(2020, Calendar.DECEMBER, 31);
        datePicker.setMaxDate(calendar.getTimeInMillis());
        return d;
    }

    public Dialog createTimeSetDialog (OBUtils.RunLambda completionBlock, final OBUtils.RunLambda cancelCompletionBlock)
    {
        timeSetCompletionBlock = completionBlock;
        final Calendar calendar = Calendar.getInstance();
        TimePickerDialog d = new TimePickerDialog(MainActivity.mainActivity, this, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(MainActivity.mainActivity));
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        d.setButton(DatePickerDialog.BUTTON_NEGATIVE, "Back", new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick (DialogInterface dialog, int which)
            {
                OBUtils.runOnMainThread(cancelCompletionBlock);
            }
        });
        d.setMessage("Please set the current time.\n");
        return d;
    }


    @Override
    public void onDateSet (DatePicker view, int year, int monthOfYear, int dayOfMonth)
    {
        final Activity activity = MainActivity.mainActivity;
        if (activity != null)
        {
            setDate(activity, year, monthOfYear, dayOfMonth);
        }
        if (dateSetCompletionBlock != null)
        {
            OBUtils.runOnMainThread(dateSetCompletionBlock);
        }
    }

    @Override
    public void onTimeSet (TimePicker view, int hourOfDay, int minute)
    {
        final Activity activity = MainActivity.mainActivity;
        ;
        if (activity != null)
        {
            setTime(activity, hourOfDay, minute);
        }
        if (timeSetCompletionBlock != null)
        {
            OBUtils.runOnMainThread(timeSetCompletionBlock);
        }
    }

    public static void setDate (Context context, int year, int month, int day)
    {
        Calendar c = Calendar.getInstance();
        //
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month);
        c.set(Calendar.DAY_OF_MONTH, day);
        long when = c.getTimeInMillis();
        //
        if (when / 1000 < Integer.MAX_VALUE)
        {
            try
            {
                ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).setTime(when);
            }
            catch (Exception e)
            {
                MainActivity.log("Exception caught while trying to set the Date");
//                e.printStackTrace();
            }
        }
    }

    public static void setTime (Context context, int hourOfDay, int minute)
    {
        Calendar c = Calendar.getInstance();
        //
        c.set(Calendar.HOUR_OF_DAY, hourOfDay);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long when = c.getTimeInMillis();
        //
        if (when / 1000 < Integer.MAX_VALUE)
        {
            try
            {
                ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).setTime(when);
            }
            catch (Exception e)
            {
                MainActivity.log("Exception caught while trying to set the Time");
                //e.printStackTrace();
            }
        }
    }


    public void setDateAndTimeDialog (final OBUtils.RunLambda completionBlock)
    {
        createDateSetDialog("Please set the current date.", false, new OBUtils.RunLambda()
        {
            @Override
            public void run () throws Exception
            {
                createTimeSetDialog(completionBlock, new OBUtils.RunLambda()
                {
                    @Override
                    public void run () throws Exception
                    {
                        setDateAndTimeDialog(completionBlock);
                    }
                }).show();
            }
        }).show();
    }


    public String getBatterySettingKeyForCurrentLevel ()
    {
        Map<String, Object> dictionary = (Map<String, Object>) MainActivity.Config().get(MainActivity.CONFIG_BATTERY_LEVELS);
        if (dictionary == null)
        {
            MainActivity.log("OBSystemsManager:getBatterySettingKeyForCurrentLevel: unable to find battery dictionary in settings");
            return null;
        }
        float batteryLevel = batteryReceiver.getBatteryLevel();
        //
        MainActivity.log("OBSystemsManager:getBatterySettingKeyForCurrentLevel: " + batteryLevel + "%");
        //
        for (String levelKey : dictionary.keySet())
        {
            Map<String, Object> dictionaryForLevel = (Map<String, Object>) dictionary.get(levelKey);
            //
            float maxLevel = Float.parseFloat((String) dictionaryForLevel.get(MainActivity.CONFIG_MAXIMUM_BATTERY_VALUE));
            float minLevel = Float.parseFloat((String) dictionaryForLevel.get(MainActivity.CONFIG_MINIMUM_BATTERY_VALUE));
            //
            if (batteryLevel > minLevel && batteryLevel <= maxLevel)
            {
                MainActivity.log("OBSystemsManager:getBatterySettingKeyForCurrentLevel: " + levelKey);
                return levelKey;
            }
        }
        //
        MainActivity.log("OBSystemsManager:getBatterySettingKeyForCurrentLevel: unable to find level key for battery");
        return null;
    }


    /**
     *
     * @return dictionary of settings for the current battery level
     */
    public Map<String, Object> getBatterySettingsForCurrentLevel ()
    {
        Map<String, Object> dictionary = (Map<String, Object>) MainActivity.Config().get(MainActivity.CONFIG_BATTERY_LEVELS);
        if(dictionary == null) return null;
        String settingKey = getBatterySettingKeyForCurrentLevel();
        //
        if (settingKey == null) return null;
        //
        return (Map<String, Object>) dictionary.get(settingKey);
    }

    /**
     * @return float (0 to 1), indicating the relative brightness of the screen
     */
    public float getMaxScreenBrightnessForCurrentLevel ()
    {
        Map<String, Object> settings = getBatterySettingsForCurrentLevel();
        if (settings == null)
        {
            String value = (String) MainActivity.Config().get(MainActivity.CONFIG_DEFAULT_MAX_BRIGHTNESS);
            if (value == null)
            {
                return 1.0f;
            }
            return Float.parseFloat(value);
        }
        else
        {
            return Float.parseFloat((String) settings.get(MainActivity.CONFIG_MAX_BRIGHTNESS));
        }
    }

    /**
     * @return minutes it takes to lock the screen due to inactivity
     */
    public int getMaxScreenTimeoutForCurrentLevel ()
    {
        Map<String, Object> settings = getBatterySettingsForCurrentLevel();
        if (settings == null)
        {
            String value = (String) MainActivity.Config().get(MainActivity.CONFIG_DEFAULT_MAX_SCREEN_TIMEOUT);
            if (value == null)
            {
                return 1;
            }
            return Integer.parseInt(value);
        }
        else
        {
            return Integer.parseInt((String) settings.get(MainActivity.CONFIG_MAX_SCREEN_TIMEOUT));
        }
    }

    /**
     * @return boolean value indicating if it should blink the battery indicator red and/or show Presenter audio to charge device
     */
    public boolean shouldShowBatterReminderForCurrentLevel ()
    {
        Map<String, Object> settings = getBatterySettingsForCurrentLevel();
        if (settings == null)
        {
            return false;
        }
        else
        {
            return settings.get(MainActivity.CONFIG_CHARGE_BATTERY_REMINDER).equals("true");
        }
    }


    /**
     *
     * @return minutes between reminders to charge the device
     */
    public int getBatteryReminderIntervalForCurrentLevel()
    {
        Map<String, Object> settings = getBatterySettingsForCurrentLevel();
        if (settings == null)
        {
            return 0;
        }
        else
        {
            return Integer.parseInt((String)settings.get(MainActivity.CONFIG_CHARGE_BATTERY_REMINDER_INTERVAL));
        }
    }

    /**
     * @return boolean value indicating if the application should lock any further activity pending charging
     */
    public boolean shouldShowBatteryLockScreenForCurrentLevel ()
    {
        Map<String, Object> settings = getBatterySettingsForCurrentLevel();
        if (settings == null)
        {
            return false;
        }
        else
        {
            return settings.get(MainActivity.CONFIG_SHOW_BATTERY_LOCK_SCREEN).equals("true");
        }
    }

    /**
     * @return battery level on 0.0-100.0 scale, -1 if no battery data
     */
    public float getBatteryLevel ()
    {
       if(batteryReceiver != null)
       {
           return batteryReceiver.getBatteryLevel();
       }
       return -1;
    }

    /**
     * @return true if battery is charging(cable is plugged in)
     */
    public boolean isBatteryCharging ()
    {
        if(batteryReceiver != null)
        {
            return batteryReceiver.cablePluggedIn();
        }
        return false;
    }

}
