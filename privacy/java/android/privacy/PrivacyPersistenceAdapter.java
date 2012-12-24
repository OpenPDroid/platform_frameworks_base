package android.privacy;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.FileUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Responsible for persisting privacy settings to built-in memory
 * 
 * @author Svyatoslav Hresyk {@hide}
 */
public final class PrivacyPersistenceAdapter {

    private static final String TAG = "PrivacyPersistenceAdapter";
    private static final int RETRY_QUERY_COUNT = 5;
    private static final String DATABASE_FILE = "/data/system/privacy.db";
    private static final int DATABASE_VERSION = 4;
    public static final int DUMMY_UID = -1;

    /**
     * Number of threads currently reading the database Could probably be
     * improved by using 'AtomicInteger'
     */
    public static volatile Integer readingThreads = 0;
    public static volatile int sDbVersion;

    // Used to lock the database during multi-statement operations to prevent
    // internally inconsistent data reads.
    private static ReadWriteLock dbLock = new ReentrantReadWriteLock();

    /**
     * Used to save settings for access from core libraries
     */
    public static final String SETTINGS_DIRECTORY = "/data/system/privacy";

    private static final String TABLE_SETTINGS = "settings";
    private static final String TABLE_MAP = "map";
    private static final String TABLE_ALLOWED_CONTACTS = "allowed_contacts";
    private static final String TABLE_VERSION = "version";

    private static final String CREATE_TABLE_SETTINGS = 
        "CREATE TABLE IF NOT EXISTS " + TABLE_SETTINGS + " ( " + 
        " _id INTEGER PRIMARY KEY AUTOINCREMENT, " + 
        " packageName TEXT, " + 
        " uid INTEGER, " + 
        " deviceIdSetting INTEGER, " + 
        " deviceId TEXT, " + 
        " line1NumberSetting INTEGER, " + 
        " line1Number TEXT, " + 
        " locationGpsSetting INTEGER, " + 
        " locationGpsLat TEXT, " + 
        " locationGpsLon TEXT, " + 
        " locationNetworkSetting INTEGER, " + 
        " locationNetworkLat TEXT, " + 
        " locationNetworkLon TEXT, " + 
        " networkInfoSetting INTEGER, " + 
        " simInfoSetting INTEGER, " + 
        " simSerialNumberSetting INTEGER, " + 
        " simSerialNumber TEXT, " + 
        " subscriberIdSetting INTEGER, " + 
        " subscriberId TEXT, " + 
        " accountsSetting INTEGER, " + 
        " accountsAuthTokensSetting INTEGER, " + 
        " outgoingCallsSetting INTEGER, " + 
        " incomingCallsSetting INTEGER, " + 
        " contactsSetting INTEGER, " + 
        " calendarSetting INTEGER, " + 
        " mmsSetting INTEGER, " + 
        " smsSetting INTEGER, " + 
        " callLogSetting INTEGER, " + 
        " bookmarksSetting INTEGER, " + 
        " systemLogsSetting INTEGER, " + 
        " externalStorageSetting INTEGER, " + 
        " cameraSetting INTEGER, " + 
        " recordAudioSetting INTEGER, " + 
        " notificationSetting INTEGER, " + 
        " intentBootCompletedSetting INTEGER," + 
        " smsSendSetting INTEGER," + 
        " phoneCallSetting INTEGER," +
        " ipTableProtectSetting INTEGER," +
        " iccAccessSetting INTEGER," +
        " addOnManagementSetting INTEGER," + //this setting indicate if app is managed by addon or not
        " androidIdSetting INTEGER," +
        " androidId TEXT," +
        " wifiInfoSetting INTEGER," +
        " switchConnectivitySetting INTEGER," +
        " sendMmsSetting INTEGER," +
        " forceOnlineState INTEGER," + 
        " switchWifiStateSetting INTEGER" +
        ");";
    

    private static final String CREATE_TABLE_MAP = 
        "CREATE TABLE IF NOT EXISTS " + TABLE_MAP + " ( name TEXT PRIMARY KEY, value TEXT );";
    
    private static final String CREATE_TABLE_ALLOWED_CONTACTS = 
        "CREATE TABLE IF NOT EXISTS " + TABLE_ALLOWED_CONTACTS + " ( settings_id, contact_id, PRIMARY KEY(settings_id, contact_id) );";

    private static final String INSERT_VERSION = 
        "INSERT OR REPLACE INTO " + TABLE_MAP + " (name, value) " + "VALUES (\"db_version\", " + DATABASE_VERSION + ");";

    private static final String INSERT_ENABLED = 
        "INSERT OR REPLACE INTO " + TABLE_MAP + " (name, value) " + "VALUES (\"enabled\", \"1\");";

    private static final String INSERT_NOTIFICATIONS_ENABLED = 
        "INSERT OR REPLACE INTO " + TABLE_MAP + " (name, value) " + "VALUES (\"notifications_enabled\", \"1\");";

    private static final String[] DATABASE_FIELDS = new String[] { "_id", "packageName", "uid",
            "deviceIdSetting", "deviceId", "line1NumberSetting", "line1Number",
            "locationGpsSetting", "locationGpsLat", "locationGpsLon", "locationNetworkSetting",
            "locationNetworkLat", "locationNetworkLon", "networkInfoSetting", "simInfoSetting",
            "simSerialNumberSetting", "simSerialNumber", "subscriberIdSetting", "subscriberId",
            "accountsSetting", "accountsAuthTokensSetting", "outgoingCallsSetting",
            "incomingCallsSetting", "contactsSetting", "calendarSetting", "mmsSetting",
            "smsSetting", "callLogSetting", "bookmarksSetting", "systemLogsSetting",
            "externalStorageSetting", "cameraSetting", "recordAudioSetting", "notificationSetting",
            "intentBootCompletedSetting", "smsSendSetting", "phoneCallSetting",
            "ipTableProtectSetting", "iccAccessSetting", "addOnManagementSetting",
            "androidIdSetting", "androidId", "wifiInfoSetting", "switchConnectivitySetting",
            "sendMmsSetting", "forceOnlineState", "switchWifiStateSetting" };

    public static final String SETTING_ENABLED = "enabled";
    public static final String SETTING_NOTIFICATIONS_ENABLED = "notifications_enabled";
    public static final String SETTING_DB_VERSION = "db_version";
    public static final String VALUE_TRUE = "1";
    public static final String VALUE_FALSE = "0";

    private SQLiteDatabase db;

    private Context context;

    public PrivacyPersistenceAdapter(Context context) {
        this.context = context;

        // create the database and settings directory if we have write
        // permission and they do not exist
        if (new File("/data/system/").canWrite()) {
            if (!new File(DATABASE_FILE).exists()) {
                createDatabase();
            }
            if (!new File(SETTINGS_DIRECTORY).exists()) {
                createSettingsDir();
            }

            dbLock.readLock().lock();

            // upgrade if needed
            sDbVersion = getDbVersion();
            if (sDbVersion < DATABASE_VERSION) {
                dbLock.readLock().unlock();
                upgradeDatabase();
            } else {
                dbLock.readLock().unlock();
            }
        }
    }

    private void upgradeDatabase() {
        dbLock.writeLock().lock();
        try {
            if (sDbVersion < DATABASE_VERSION) {
                Log.i(TAG, "upgradeDatabase - upgrading DB from version " + sDbVersion + " to "
                        + DATABASE_VERSION);

                // backup current database file
                File dbFile = new File(DATABASE_FILE);
                File dbBackupFile = new File(DATABASE_FILE + ".bak");
                // remove old backup
                try {
                    dbBackupFile.delete();
                } catch (SecurityException e) {
                    Log.w(TAG,
                            "PrivacyPersistenceAdapter:upgradeDatabase: Could not remove old backup");
                }

                // create a database backup and check it was created
                // (in case upgrade fails, in which case the DB is restored).
                FileUtils.copyFile(dbFile, dbBackupFile);
                if (System.currentTimeMillis() - dbBackupFile.lastModified() > 2000) {
                    /*
                     * TODO: Add exception handling, so the parent function
                     * knows if the upgrade fails rather than just continuing on
                     * a non-upgraded database
                     */
                    Log.e(TAG,
                            "PrivacyPersistenceAdapter:upgradeDatabase: Could not create database backup. Aborting upgrade.");
                } else {

                    SQLiteDatabase db = getWritableDatabase();
                    db.beginTransaction();

                    switch (sDbVersion) {
                    case 1:
                    case 2:
                    case 3:
                        try {
                            if (db != null && db.isOpen()) {
                                db.execSQL("DROP TABLE IF EXISTS " + TABLE_VERSION + ";");
                                db.execSQL(CREATE_TABLE_ALLOWED_CONTACTS);
                                db.execSQL(CREATE_TABLE_MAP);
                                db.execSQL(INSERT_VERSION);
                                db.execSQL(INSERT_ENABLED);
                                db.execSQL(INSERT_NOTIFICATIONS_ENABLED);

                                // remove uid dirs from the settings directory
                                File settingsDir = new File(SETTINGS_DIRECTORY);
                                for (File packageDir : settingsDir.listFiles()) {
                                    for (File uidDir : packageDir.listFiles()) {
                                        if (uidDir.isDirectory()) {
                                            File[] settingsFiles = uidDir.listFiles();
                                            // copy the first found (most likely
                                            // the
                                            // only
                                            // one) one level up
                                            if (settingsFiles[0] != null) {
                                                File newPath = new File(packageDir + "/"
                                                        + settingsFiles[0].getName());
                                                newPath.delete();
                                                settingsFiles[0].renameTo(newPath);
                                                deleteRecursive(uidDir);
                                            }
                                        }
                                    }
                                }

                                db.setTransactionSuccessful();
                                sDbVersion = DATABASE_VERSION;
                            }
                        } catch (Exception e) {
                            if (db != null && db.isOpen()) {
                                db.endTransaction();
                                db.close();
                            }
                            Log.w(TAG,
                                    "upgradeDatabase - could not upgrade DB; will restore backup",
                                    e);
                            FileUtils.copyFile(dbBackupFile, dbFile);
                            dbBackupFile.delete();
                        }

                        break;

                    case 4:
                        // most current version, do nothing
                        Log.w(TAG, "upgradeDatabase - trying to upgrade most current DB version");
                        break;
                    }
                }
                if (db != null && db.isOpen()) {
                    db.endTransaction();
                    db.close();
                } else {
                    Log.e(TAG,
                            "upgradeDatabase - database is null or closed; cant call endTransaction()");
                }

                purgeSettings();
            }
        } finally {
            dbLock.writeLock().unlock();
        }
    }

    private int getDbVersion() {
        int version = -1;
        // check if the table "map" exists; if it doesn't -> return version 1
        synchronized (readingThreads) {
            readingThreads++;
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;

        try {
            c = rawQuery(db, "SELECT name FROM sqlite_master WHERE type='table' AND name='"
                    + TABLE_MAP + "';");
            if (c != null) {
                if (c.getCount() == 0) {
                    // table map does not exist
                    version = 1;
                }
            } else {
                Log.e(TAG, "getDbVersion - failed to check if table map exists (cursor is null)");
            }
        } catch (Exception e) {
            Log.e(TAG, "getDbVersion - failed to check if table map exists (exception)");
        } finally {
            c.close();
            closeIdleDatabase();
        }
        if (version == 1)
            return version;

        String versionString = getValue(SETTING_DB_VERSION);
        if (versionString == null)
            return 1;

        int versionNum;
        try {
            versionNum = Integer.parseInt(versionString);
        } catch (Exception e) {
            Log.e(TAG, "getDbVersion - failed to parse database version; returning 1");
            return 1;
        }

        return versionNum;
    }

    public String getValue(String name) {

        synchronized (readingThreads) {
            readingThreads++;
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        String output = null;

        try {
            c = query(db, TABLE_MAP, new String[] { "value" }, "name=?", new String[] { name },
                    null, null, null, null);
            if (c != null && c.getCount() > 0 && c.moveToFirst()) {
                output = c.getString(c.getColumnIndex("value"));
                c.close();
            } else {
                Log.w(TAG, "getValue - could not get value for name: " + name);
            }
        } catch (Exception e) {
            Log.w(TAG, "getValue - could not get value for name: " + name, e);
        }

        closeIdleDatabase();
        return output;
    }

    public synchronized boolean setValue(String name, String value) {
        Log.e(TAG, "setValue - name " + name + " value " + value);
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("value", value);
        SQLiteDatabase db = getWritableDatabase();
        boolean success = db.replace(TABLE_MAP, null, values) != -1;
        closeIdleDatabase();
        return success;
    }

    /**
     * Retrieve privacy settings for a single package
     * 
     * @param packageName
     *            package for which to retrieve settings
     * @return privacy settings for the package, or null if no settings exist
     *         for it
     */
    public PrivacySettings getSettings(String packageName) {
        PrivacySettings privacySettings = null;

        if (packageName == null) {
            throw new InvalidParameterException(
                    "getSettings - insufficient application identifier - package name is required");
        }

        // indicate that the DB is being read to prevent closing by other
        // threads
        synchronized (readingThreads) {
            readingThreads++;
        }

        SQLiteDatabase db;
        try {
            db = getReadableDatabase();
        } catch (SQLiteException e) {
            Log.e(TAG, "getSettings - database could not be opened", e);
            closeIdleDatabase();
            return privacySettings;
        }

        Cursor cursor = null;

        dbLock.readLock().lock();
        try {
            cursor = query(db, TABLE_SETTINGS, DATABASE_FIELDS, "packageName=?",
                    new String[] { packageName }, null, null, null, null);

            if (cursor != null) {
                if (cursor.getCount() != 1) {
                    Log.w(TAG, "Multiple privacy settings found for package " + packageName);
                }
                if (cursor.moveToFirst()) {
                    privacySettings = new PrivacySettings(cursor.getInt(0), cursor.getString(1),
                            cursor.getInt(2), (byte) cursor.getShort(3), cursor.getString(4),
                            (byte) cursor.getShort(5), cursor.getString(6),
                            (byte) cursor.getShort(7), cursor.getString(8), cursor.getString(9),
                            (byte) cursor.getShort(10), cursor.getString(11), cursor.getString(12),
                            (byte) cursor.getShort(13), (byte) cursor.getShort(14),
                            (byte) cursor.getShort(15), cursor.getString(16),
                            (byte) cursor.getShort(17), cursor.getString(18),
                            (byte) cursor.getShort(19), (byte) cursor.getShort(20),
                            (byte) cursor.getShort(21), (byte) cursor.getShort(22),
                            (byte) cursor.getShort(23), (byte) cursor.getShort(24),
                            (byte) cursor.getShort(25), (byte) cursor.getShort(26),
                            (byte) cursor.getShort(27), (byte) cursor.getShort(28),
                            (byte) cursor.getShort(29), (byte) cursor.getShort(30),
                            (byte) cursor.getShort(31), (byte) cursor.getShort(32),
                            (byte) cursor.getShort(33), (byte) cursor.getShort(34), null,
                            (byte) cursor.getShort(35), (byte) cursor.getShort(36),
                            (byte) cursor.getShort(37), (byte) cursor.getShort(38),
                            (byte) cursor.getShort(39), (byte) cursor.getShort(40),
                            cursor.getString(41), (byte) cursor.getShort(42),
                            (byte) cursor.getShort(43), (byte) cursor.getShort(44),
                            (byte) cursor.getShort(45), (byte) cursor.getShort(46));

                    // get allowed contacts IDs if necessary
                    cursor = query(db, TABLE_ALLOWED_CONTACTS, new String[] { "contact_id" },
                            "settings_id=?",
                            new String[] { Integer.toString(privacySettings.get_id()) }, null,
                            null, null, null);

                    if (cursor != null && cursor.getCount() > 0) {
                        int[] allowedContacts = new int[cursor.getCount()];
                        while (cursor.moveToNext())
                            allowedContacts[cursor.getPosition()] = cursor.getInt(0);
                        privacySettings.setAllowedContacts(allowedContacts);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getSettings - failed to get settings for package: " + packageName, e);
            e.printStackTrace();
            if (cursor != null)
                cursor.close();
        } finally {
            if (cursor != null)
                cursor.close();
            dbLock.readLock().unlock();
            closeIdleDatabase();
        }

        return privacySettings;
    }

    /**
     * Saves the settings object fields into DB and into plain text files where
     * applicable. The DB changes will not be made persistent if saving settings
     * to plain text files fails.
     * 
     * @param s
     *            settings object
     * @return true if settings were saved successfully, false otherwise
     */
    public boolean saveSettings(PrivacySettings s) {
        boolean result = true;
        String packageName = s.getPackageName();

        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "saveSettings - either package name is missing");
            return false;
        }

        ContentValues values = new ContentValues();
        values.put("packageName", packageName);
        // values.put("uid", uid);
        values.put("uid", DUMMY_UID);

        values.put("deviceIdSetting", s.getDeviceIdSetting());
        values.put("deviceId", s.getDeviceId());

        values.put("line1NumberSetting", s.getLine1NumberSetting());
        values.put("line1Number", s.getLine1Number());

        values.put("locationGpsSetting", s.getLocationGpsSetting());
        values.put("locationGpsLat", s.getLocationGpsLat());
        values.put("locationGpsLon", s.getLocationGpsLon());

        values.put("locationNetworkSetting", s.getLocationNetworkSetting());
        values.put("locationNetworkLat", s.getLocationNetworkLat());
        values.put("locationNetworkLon", s.getLocationNetworkLon());

        values.put("networkInfoSetting", s.getNetworkInfoSetting());
        values.put("simInfoSetting", s.getSimInfoSetting());

        values.put("simSerialNumberSetting", s.getSimSerialNumberSetting());
        values.put("simSerialNumber", s.getSimSerialNumber());
        values.put("subscriberIdSetting", s.getSubscriberIdSetting());
        values.put("subscriberId", s.getSubscriberId());

        values.put("accountsSetting", s.getAccountsSetting());
        values.put("accountsAuthTokensSetting", s.getAccountsAuthTokensSetting());
        values.put("outgoingCallsSetting", s.getOutgoingCallsSetting());
        values.put("incomingCallsSetting", s.getIncomingCallsSetting());

        values.put("contactsSetting", s.getContactsSetting());
        values.put("calendarSetting", s.getCalendarSetting());
        values.put("mmsSetting", s.getMmsSetting());
        values.put("smsSetting", s.getSmsSetting());
        values.put("callLogSetting", s.getCallLogSetting());
        values.put("bookmarksSetting", s.getBookmarksSetting());
        values.put("systemLogsSetting", s.getSystemLogsSetting());
        values.put("notificationSetting", s.getNotificationSetting());
        values.put("intentBootCompletedSetting", s.getIntentBootCompletedSetting());
        // values.put("externalStorageSetting", s.getExternalStorageSetting());
        values.put("cameraSetting", s.getCameraSetting());
        values.put("recordAudioSetting", s.getRecordAudioSetting());
        values.put("smsSendSetting", s.getSmsSendSetting());
        values.put("phoneCallSetting", s.getPhoneCallSetting());
        values.put("ipTableProtectSetting", s.getIpTableProtectSetting());
        values.put("iccAccessSetting", s.getIccAccessSetting());
        values.put("addOnManagementSetting", s.getAddOnManagementSetting());
        values.put("androidIdSetting", s.getAndroidIdSetting());
        values.put("androidId", s.getAndroidID());
        values.put("wifiInfoSetting", s.getWifiInfoSetting());
        values.put("switchConnectivitySetting", s.getSwitchConnectivitySetting());
        values.put("sendMmsSetting", s.getSendMmsSetting());
        values.put("forceOnlineState", s.getForceOnlineState());
        values.put("switchWifiStateSetting", s.getSwitchWifiStateSetting());

        synchronized (readingThreads) {
            readingThreads++;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction(); // make sure this ends up in a consistent state
                               // (DB and plain text files)
        Cursor c = null;
        dbLock.writeLock().lock();
        try {
            // save settings to the DB
            // Log.d(TAG, "saveSettings - checking if entry exists already");
            Integer id = s.get_id();
            if (id != null) { // existing entry -> update
                // Log.d(TAG, "saveSettings - updating existing entry");
                if (db.update(TABLE_SETTINGS, values, "_id=?", new String[] { id.toString() }) < 1) {
                    throw new Exception("saveSettings - failed to update database entry");
                }

                db.delete(TABLE_ALLOWED_CONTACTS, "settings_id=?", new String[] { id.toString() });
                int[] allowedContacts = s.getAllowedContacts();
                if (allowedContacts != null) {
                    ContentValues contactsValues = new ContentValues();
                    for (int i = 0; i < allowedContacts.length; i++) {
                        contactsValues.put("settings_id", id);
                        contactsValues.put("contact_id", allowedContacts[i]);
                        if (db.insert(TABLE_ALLOWED_CONTACTS, null, contactsValues) == -1)
                            throw new Exception(
                                    "saveSettings - failed to update database entry (contacts)");
                    }
                }

            } else { // new entry -> insert if no duplicates exist
                // Log.d(TAG,
                // "saveSettings - new entry; verifying if duplicates exist");
                c = db.query(TABLE_SETTINGS, new String[] { "_id" }, "packageName=?",
                        new String[] { s.getPackageName() }, null, null, null);

                if (c != null) {
                    if (c.getCount() == 1) { // exactly one entry
                        // exists -> update
                        // Log.d(TAG, "saveSettings - updating existing entry");
                        if (db.update(TABLE_SETTINGS, values, "packageName=?",
                                new String[] { s.getPackageName() }) < 1) {
                            throw new Exception("saveSettings - failed to update database entry");
                        }

                        if (c.moveToFirst()) {
                            Integer idAlt = c.getInt(0); // id of the found
                                                         // duplicate entry
                            db.delete(TABLE_ALLOWED_CONTACTS, "settings_id=?",
                                    new String[] { idAlt.toString() });
                            int[] allowedContacts = s.getAllowedContacts();
                            if (allowedContacts != null) {
                                ContentValues contactsValues = new ContentValues();
                                for (int i = 0; i < allowedContacts.length; i++) {
                                    contactsValues.put("settings_id", idAlt);
                                    contactsValues.put("contact_id", allowedContacts[i]);
                                    if (db.insert(TABLE_ALLOWED_CONTACTS, null, contactsValues) == -1)
                                        throw new Exception(
                                                "saveSettings - failed to update database entry (contacts)");
                                }
                            }
                        }
                    } else if (c.getCount() == 0) { // no entries -> insert
                        // Log.d(TAG, "saveSettings - inserting new entry");
                        long rowId = db.insert(TABLE_SETTINGS, null, values);
                        if (rowId == -1) {
                            throw new Exception(
                                    "saveSettings - failed to insert new record into DB");
                        }

                        db.delete(TABLE_ALLOWED_CONTACTS, "settings_id=?",
                                new String[] { Long.toString(rowId) });
                        int[] allowedContacts = s.getAllowedContacts();
                        if (allowedContacts != null) {
                            ContentValues contactsValues = new ContentValues();
                            for (int i = 0; i < allowedContacts.length; i++) {
                                contactsValues.put("settings_id", rowId);
                                contactsValues.put("contact_id", allowedContacts[i]);
                                if (db.insert(TABLE_ALLOWED_CONTACTS, null, contactsValues) == -1)
                                    throw new Exception(
                                            "saveSettings - failed to update database entry (contacts)");
                            }
                        }
                    } else { // something went totally wrong and there are
                             // multiple entries for same identifier
                        result = false;
                        throw new Exception("saveSettings - duplicate entries in the privacy.db");
                    }
                } else {
                    result = false;
                    // jump to catch block to avoid marking transaction as
                    // successful
                    throw new Exception("saveSettings - cursor is null, database access failed");
                }
            }

            // save settings to plain text file (for access from core libraries)
            result = writeExternalSettings("systemLogsSetting", packageName, s);
            result = writeExternalSettings("ipTableProtectSetting", packageName, s);

            // mark DB transaction successful (commit the changes)
            db.setTransactionSuccessful();
            // Log.d(TAG, "saveSettings - completing transaction");
        } catch (Exception e) {
            result = false;
            // Log.d(TAG, "saveSettings - could not save settings", e);
        } finally {
            if (c != null)
                c.close();
            dbLock.writeLock().unlock();
            db.endTransaction();
            closeIdleDatabase();
        }

        return result;
    }

    /**
     * This method creates external settings files for access from core librarys
     * 
     * @param settingsName
     *            field name from database
     * @param packageName
     *            name of package
     * @param s
     *            settings from package
     * @return true if file was successful written
     * @throws Exception
     *             if we cannot write settings to directory
     */
    private boolean writeExternalSettings(String settingsName, String packageName, PrivacySettings s)
            throws Exception {
        // save settings to plain text file (for access from core libraries)
        File settingsPackageDir = new File("/data/system/privacy/" + packageName + "/");
        File systemLogsSettingFile = new File("/data/system/privacy/" + packageName + "/" + "/"
                + settingsName);
        try {
            settingsPackageDir.mkdirs();
            settingsPackageDir.setReadable(true, false);
            settingsPackageDir.setExecutable(true, false);
            // create the setting files and make them readable
            systemLogsSettingFile.createNewFile();
            systemLogsSettingFile.setReadable(true, false);
            // write settings to files
            // Log.d(TAG, "saveSettings - writing to file");
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(
                    systemLogsSettingFile));
            // now decide which feature of setting we have to save
            if (settingsName.equals("systemLogsSetting"))
                writer.append(s.getSystemLogsSetting() + "");
            else if (settingsName.equals("ipTableProtectSetting"))
                writer.append(s.getIpTableProtectSetting() + "");
            writer.flush();
            writer.close();
            return true;
        } catch (IOException e) {
            // jump to catch block to avoid marking transaction as successful
            throw new Exception("saveSettings - could not write settings to file", e);
        }
    }

    /**
     * Deletes a settings entry from the DB
     * 
     * @return true if settings were deleted successfully, false otherwise
     */
    public boolean deleteSettings(String packageName) {
        boolean result = true;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction(); // make sure this ends up in a consistent state

        dbLock.writeLock().lock();
        try {
            // try deleting contacts allowed entries; do not fail if deletion
            // not possible
            // TODO: restructure this into a more efficient query (ideally a
            // single query without a cursor)
            Cursor c = db.query(TABLE_SETTINGS, new String[] { "_id" }, "packageName=?",
                    new String[] { packageName }, null, null, null);
            
            
            if (c != null && c.getCount() > 0 && c.moveToFirst()) {
                int id = c.getInt(0);
                db.delete(TABLE_ALLOWED_CONTACTS, "settings_id=?",
                        new String[] { Integer.toString(id) });
                c.close();
            } else {
                Log.e(TAG, "deleteSettings - database entry for " + packageName + " not found");
            }

            if (db.delete(TABLE_SETTINGS, "packageName=?", new String[] { packageName }) == 0) {
                Log.e(TAG, "deleteSettings - database entry for " + packageName + " not found");
            }
            db.setTransactionSuccessful();

            // delete settings from plain text file (for access from core
            // libraries)
            File settingsPackageDir = new File("/data/system/privacy/" + packageName + "/");
            File systemLogsSettingFile = new File("/data/system/privacy/" + packageName
                    + "/systemLogsSetting");
            
            // delete the setting files
            systemLogsSettingFile.delete();
            // delete the parent directories
            if (settingsPackageDir.list() == null || settingsPackageDir.list().length == 0)
                settingsPackageDir.delete();
            
        } catch (Exception e) {
            result = false;
            Log.e(TAG, "deleteSettings - could not delete settings", e);
        } finally {
            dbLock.writeLock().unlock();
            db.endTransaction();
            if (db != null && db.isOpen())
                db.close();
        }

        return result;
    }

    private Cursor query(SQLiteDatabase db, String table, String[] columns, String selection,
            String[] selectionArgs, String groupBy, String having, String orderBy, String limit)
            throws Exception {
        Cursor c = null;
        // make sure getting settings does not fail because of
        // IllegalStateException (db already closed)
        boolean success = false;
        for (int i = 0; success == false && i < RETRY_QUERY_COUNT; i++) {
            try {
                if (c != null)
                    c.close();
                c = db.query(table, columns, selection, selectionArgs, groupBy, having, orderBy,
                        limit);
                success = true;
            } catch (IllegalStateException e) {
                success = false;
                if (db != null && db.isOpen())
                    db.close();
                db = getReadableDatabase();
            }
        }
        if (success == false)
            throw new Exception("query - failed to execute query on the DB");
        return c;
    }

    private Cursor rawQuery(SQLiteDatabase db, String sql) throws Exception {
        Cursor c = null;
        // make sure getting settings does not fail because of
        // IllegalStateException (db already closed)
        boolean success = false;
        for (int i = 0; success == false && i < RETRY_QUERY_COUNT; i++) {
            try {
                if (c != null)
                    c.close();
                c = db.rawQuery(sql, null);
                success = true;
            } catch (IllegalStateException e) {
                success = false;
                if (db != null && db.isOpen())
                    db.close();
                db = getReadableDatabase();
            }
        }
        if (success == false)
            throw new Exception("query - failed to execute query on the DB");
        return c;
    }

    /**
     * Removes obsolete entries from the DB and file system. Should not be used
     * in methods, which rely on the DB being open after this method has
     * finished. It will close the DB if no other threads has increased the
     * readingThread count.
     * 
     * @return true if purge was successful, false otherwise.
     */
    public boolean purgeSettings() {
        boolean result = true;
        // Log.d(TAG, "purgeSettings - begin purging settings");
        // get installed apps
        List<String> apps = new ArrayList<String>();
        PackageManager pMan = context.getPackageManager();
        List<ApplicationInfo> installedApps = pMan
                .getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo appInfo : installedApps) {
            apps.add(appInfo.packageName);
        }

        dbLock.writeLock().lock();
        // Log.d(TAG, "purgeSettings - purging directories");
        // delete obsolete settings directories
        File settingsDir = new File(SETTINGS_DIRECTORY);
        for (File packageDir : settingsDir.listFiles()) {
            String packageName = packageDir.getName();
            // Log.d(TAG, "purgeSettings - checking package directory " +
            // packageName);

            if (!apps.contains(packageName)) { // remove package dir if no such
                                               // app installed
                // Log.d(TAG, "purgeSettings - deleting " + packageName);
                deleteRecursive(packageDir);
            }
        }

        // Log.d(TAG, "purgeSettings - purging database");
        // delete obsolete entries from DB and update outdated entries
        synchronized (readingThreads) {
            readingThreads++;
        }
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = query(db, TABLE_SETTINGS, new String[] { "packageName" }, null, null, null, null,
                    null, null);
            // Log.d(TAG, "purgeSettings - found " + c.getCount() +
            // " entries in the DB");
            List<String> appsInDb = new ArrayList<String>();
            while (c.moveToNext()) {
                String packageName = c.getString(0);
                if (!apps.contains(packageName)) {
                    deleteSettings(packageName);
                } else {
                    if (appsInDb.contains(packageName)) { // if duplicate entry,
                                                          // remove all
                                                          // duplicates and
                                                          // keep only one
                        PrivacySettings pSetTmp = getSettings(packageName);
                        deleteSettings(packageName);
                        saveSettings(pSetTmp);
                    } else {
                        appsInDb.add(packageName);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "purgeSettings - purging DB failed", e);
            result = false;
        } finally {
            if (c != null)
                c.close();
            dbLock.writeLock().unlock();
            closeIdleDatabase();
        }
        return result;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles())
                deleteRecursive(child);
        }
        fileOrDirectory.delete();
    }

    private synchronized void createDatabase() {
        Log.i(TAG, "createDatabase - creating privacy database file");
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(DATABASE_FILE, null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
            Log.i(TAG, "createDatabase - creating privacy database");
            db.execSQL(CREATE_TABLE_SETTINGS);
            db.execSQL(CREATE_TABLE_ALLOWED_CONTACTS);
            db.execSQL(CREATE_TABLE_MAP);
            db.execSQL(INSERT_VERSION);
            db.execSQL(INSERT_ENABLED);
            db.execSQL(INSERT_NOTIFICATIONS_ENABLED);
            // Log.d(TAG, "createDatabase - closing connection to privacy.db");
            if (db != null && db.isOpen())
                db.close();
        } catch (SQLException e) {
            Log.e(TAG, "createDatabase - failed to create privacy database", e);
        }
    }

    private synchronized void createSettingsDir() {
        // create settings directory (for settings accessed from core libraries)
        File settingsDir = new File("/data/system/privacy/");
        settingsDir.mkdirs();
        // make it readable for everybody
        settingsDir.setReadable(true, false);
        settingsDir.setExecutable(true, false);
    }

    private synchronized SQLiteDatabase getReadableDatabase() {
        if (db != null && db.isOpen())
            return db;

        db = SQLiteDatabase.openDatabase(DATABASE_FILE, null, SQLiteDatabase.OPEN_READONLY);

        return db;
    }

    private synchronized SQLiteDatabase getWritableDatabase() {
        // create the database if it does not exist
        if (!new File(DATABASE_FILE).exists())
            createDatabase();

        if (db != null && db.isOpen() && !db.isReadOnly()) {
            return db;
        }

        db = SQLiteDatabase.openDatabase(DATABASE_FILE, null, SQLiteDatabase.OPEN_READWRITE);

        return db;
    }

    /**
     * If there are no more threads reading the database, close it. Otherwise,
     * reduce the number of reading threads by one
     */
    private void closeIdleDatabase() {
        synchronized (readingThreads) {
            readingThreads--;
            // only close DB if no other threads are reading
            if (readingThreads == 0 && db != null && db.isOpen()) {
                db.close();
            }
        }
    }
}
