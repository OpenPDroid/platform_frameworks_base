/**
 * Copyright (C) 2012 Svyatoslav Hresyk
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, see <http://www.gnu.org/licenses>.
 */

package android.privacy.surrogate;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.IAccountManager;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.ServiceManager;
import android.privacy.IPrivacySettingsManager;
import android.privacy.PrivacyServiceException;
import android.privacy.PrivacySettings;
import android.privacy.PrivacySettingsManager;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Provides privacy handling for {@link android.accounts.AccountManager}
 * @author Svyatoslav Hresyk
 * {@hide}
 */
public final class PrivacyAccountManager extends AccountManager {

    private static final String TAG = "PrivacyAccountManager";

    private Context context;

    private PrivacySettingsManager pSetMan;

    /** {@hide} */
    public PrivacyAccountManager(Context context, IAccountManager service) {
        super(context, service);
        this.context = context;
    }

    /** {@hide} */
    public PrivacyAccountManager(Context context, IAccountManager service, Handler handler) {
        super(context, service, handler);
        this.context = context;
    }

    /**
     * GET_ACCOUNTS
     */

    @Override
    public Account[] getAccounts() {
        String packageName = context.getPackageName();
        String output_label;
        Account[] output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);

            if (pSet != null && pSet.getAccountsSetting() != PrivacySettings.REAL) {
                output_label = "[empty accounts list]";
                output = new Account[0];
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);
            } else {
                output_label = "[real value]";
                output = super.getAccounts(); 
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:getAccounts: PrivacyServiceException occurred");
            output_label = "[empty accounts list]";
            output = new Account[0];
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_ACCOUNTS_LIST, null);
        }
        return output;
    }

    @Override
    public Account[] getAccountsByType(String type) {
        String packageName = context.getPackageName();

        String output_label;
        Account[] output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);

            if (pSet != null && pSet.getAccountsSetting() != PrivacySettings.REAL) {
                output_label = "[empty accounts list]";
                output = new Account[0];
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);
            } else {
                output_label = "[real value]";
                output = super.getAccountsByType(type);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:getAccountsByType: PrivacyServiceException occurred");
            output_label = "[empty accounts list]";
            output = new Account[0];
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_ACCOUNTS_LIST, null);
        }

        return output;
    }

    @Override
    public AccountManagerFuture<Boolean> hasFeatures(Account account, String[] features,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        String packageName = context.getPackageName();

        String output_label;
        AccountManagerFuture<Boolean> output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);        
            if (pSet != null && pSet.getAccountsSetting() != PrivacySettings.REAL) {
                output_label = "[false]";
                output = new PrivacyAccountManagerFuture<Boolean>(false);
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);      
            } else {
                output_label = "[real value]";
                output = super.hasFeatures(account, features, callback, handler);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);            
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:hasFeatures: PrivacyServiceException occurred");
            output_label = "[false]";
            output = new PrivacyAccountManagerFuture<Boolean>(false);
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_ACCOUNTS_LIST, null);
        }

        return output;
    }

    @Override
    public AccountManagerFuture<Account[]> getAccountsByTypeAndFeatures(String type, 
            String[] features, AccountManagerCallback<Account[]> callback, Handler handler) {

        String packageName = context.getPackageName();
        String output_label;
        AccountManagerFuture<Account[]> output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);       

            if (pSet != null && pSet.getAccountsSetting() != PrivacySettings.REAL) {
                output_label = "[false]";
                output = new PrivacyAccountManagerFuture<Account[]>(new Account[0]);
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);      
            } else {
                output_label = "[real value]";
                output = super.getAccountsByTypeAndFeatures(type, features, callback, handler);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_ACCOUNTS_LIST, null);            
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:getAccountsByTypeAndFeatures: "
                    + "PrivacyServiceException occurred");
            output_label = "[false]";
            output = new PrivacyAccountManagerFuture<Account[]>(new Account[0]);
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_ACCOUNTS_LIST, null);
        }

        return output;
    }

    /**
     * USE_CREDENTIALS
     */

    @Override
    public String blockingGetAuthToken(Account account, String authTokenType, 
            boolean notifyAuthFailure)
            throws OperationCanceledException, IOException, AuthenticatorException {

        String packageName = context.getPackageName();
        String output = null;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);    

            if (pSet != null && pSet.getAccountsAuthTokensSetting() != PrivacySettings.REAL) {
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            } else {
                output = super.blockingGetAuthToken(account, authTokenType, notifyAuthFailure);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:blockingGetAuthToken: "
                    + "PrivacyServiceException occurred");
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_AUTH_TOKENS, null);
        }

        return output;
    }

    @Override
    public AccountManagerFuture<Bundle> getAuthToken(Account account, String authTokenType, 
            boolean notifyAuthFailure, AccountManagerCallback<Bundle> callback, Handler handler) {

        String packageName = context.getPackageName();
        String output_label;
        AccountManagerFuture<Bundle> output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);   

            if (pSet != null && pSet.getAccountsAuthTokensSetting() != PrivacySettings.REAL) {
                output_label = "[empty]";
                output = new PrivacyAccountManagerFuture<Bundle>(new Bundle());
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            } else {
                output_label = "[real value]";
                output = super.getAuthToken(account, authTokenType, notifyAuthFailure, 
                        callback, handler);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:getAuthToken: PrivacyServiceException occurred");
            output_label = "[empty]";
            output = new PrivacyAccountManagerFuture<Bundle>(new Bundle());
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_AUTH_TOKENS, null);
        }

        return output;
    }

    @Override
    public AccountManagerFuture<Bundle> getAuthToken(Account account, String authTokenType, 
            Bundle options, Activity activity, AccountManagerCallback<Bundle> callback, 
            Handler handler) {

        String packageName = context.getPackageName();
        String output_label;
        AccountManagerFuture<Bundle> output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);   

            if (pSet != null && pSet.getAccountsAuthTokensSetting() != PrivacySettings.REAL) {
                output_label = "[empty]";
                output = new PrivacyAccountManagerFuture<Bundle>(new Bundle());
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            } else {
                output_label = "[real value]";
                output = super.getAuthToken(account, authTokenType, options, activity, 
                        callback, handler);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            }
        } catch (PrivacyServiceException e) {
            Log.e(TAG, "PrivacyAccountManager:getAuthToken: PrivacyServiceException occurred");
            output_label = "[empty]";
            output = new PrivacyAccountManagerFuture<Bundle>(new Bundle());
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_AUTH_TOKENS, null);
        }

        return output;
    }

    /**
     * MANAGE_ACCOUNTS
     */

    @Override
    public AccountManagerFuture<Bundle> getAuthTokenByFeatures(String accountType, 
            String authTokenType, String[] features, Activity activity, Bundle addAccountOptions, 
            Bundle getAuthTokenOptions, AccountManagerCallback<Bundle> callback, Handler handler) {
        String packageName = context.getPackageName();
        String output_label;
        AccountManagerFuture<Bundle> output;

        if (pSetMan == null) {
            pSetMan = PrivacySettingsManager.getPrivacyService(context);
        }
        try {
            PrivacySettings pSet = pSetMan.getSettings(packageName);   

            if (pSet != null && pSet.getAccountsAuthTokensSetting() != PrivacySettings.REAL) {
                output_label = "[empty]";
                output = new PrivacyAccountManagerFuture<Bundle>(new Bundle());
                pSetMan.notification(packageName, PrivacySettings.EMPTY, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            } else {
                output_label = "[real value]";
                output = super.getAuthTokenByFeatures(accountType, authTokenType, features, 
                        activity, addAccountOptions, getAuthTokenOptions, callback, handler);
                pSetMan.notification(packageName, PrivacySettings.REAL, 
                        PrivacySettings.DATA_AUTH_TOKENS, null);      
            }
        } catch (PrivacyServiceException e) {
            output_label = "[empty]";
            output = new PrivacyAccountManagerFuture<Bundle>(new Bundle());
            pSetMan.notification(packageName, PrivacySettings.ERROR, 
                    PrivacySettings.DATA_AUTH_TOKENS, null);
        }

        return output;
    }

    /**
     * Helper class. Used for returning custom values to AccountManager callers.
     */
    private class PrivacyAccountManagerFuture<V> implements AccountManagerFuture<V> {

        private V result;

        public PrivacyAccountManagerFuture(V result) {
            this.result = result;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public V getResult() throws OperationCanceledException, IOException, AuthenticatorException {
            return result;
        }

        @Override
        public V getResult(long timeout, TimeUnit unit) throws OperationCanceledException, 
                IOException, AuthenticatorException {
            return result;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

    }
}
