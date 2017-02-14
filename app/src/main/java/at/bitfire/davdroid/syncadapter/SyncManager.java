/*
* Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the GNU Public License v3.0
* which accompanies this distribution, and is available at
* http://www.gnu.org/licenses/gpl.html
*/
package at.bitfire.davdroid.syncadapter;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import at.bitfire.davdroid.AccountSettings;
import at.bitfire.davdroid.App;
import at.bitfire.davdroid.GsonHelper;
import at.bitfire.davdroid.HttpClient;
import at.bitfire.davdroid.InvalidAccountException;
import at.bitfire.davdroid.NotificationHelper;
import at.bitfire.davdroid.journalmanager.Exceptions;
import at.bitfire.davdroid.journalmanager.JournalEntryManager;
import at.bitfire.davdroid.model.CollectionInfo;
import at.bitfire.davdroid.resource.LocalCollection;
import at.bitfire.davdroid.resource.LocalResource;
import at.bitfire.davdroid.ui.AccountSettingsActivity;
import at.bitfire.davdroid.ui.DebugInfoActivity;
import at.bitfire.ical4android.CalendarStorageException;
import at.bitfire.ical4android.InvalidCalendarException;
import at.bitfire.vcard4android.ContactsStorageException;
import lombok.Getter;
import okhttp3.OkHttpClient;

abstract public class SyncManager {

    final static String SYNC_PHASE_PREPARE = "sync_phase_prepare",
            SYNC_PHASE_JOURNALS = "sync_phase_journals",
            SYNC_PHASE_QUERY_CAPABILITIES = "sync_phase_query_capabilities",
            SYNC_PHASE_PREPARE_LOCAL = "sync_phase_prepare_local",
            SYNC_PHASE_CREATE_LOCAL_ENTRIES = "sync_phase_create_local_entries",
            SYNC_PHASE_FETCH_ENTRIES = "sync_phase_fetch_entries",
            SYNC_PHASE_APPLY_REMOTE_ENTRIES = "sync_phase_apply_remote_entries",
            SYNC_PHASE_APPLY_LOCAL_ENTRIES = "sync_phase_apply_local_entries",
            SYNC_PHASE_PUSH_ENTRIES = "sync_phase_push_entries",
            SYNC_PHASE_POST_PROCESSING = "sync_phase_post_processing",
            SYNC_PHASE_SAVE_SYNC_TAG = "sync_phase_save_sync_tag";


    protected final NotificationHelper notificationManager;
    protected final String uniqueCollectionId;

    protected final Context context;
    protected final Account account;
    protected final Bundle extras;
    protected final String authority;
    protected final SyncResult syncResult;
    protected final CollectionInfo.Type serviceType;

    protected final AccountSettings settings;
    protected LocalCollection localCollection;

    protected OkHttpClient httpClient;

    protected JournalEntryManager journal;

    /**
     * remote CTag (uuid of the last entry on the server). We update it when we fetch/push and save when everything works.
     */
    protected String remoteCTag = null;

    /**
     * Syncable local journal entries.
     */
    protected List<JournalEntryManager.Entry> localEntries;

    /**
     * Syncable remote journal entries (fetch from server).
     */
    protected List<JournalEntryManager.Entry> remoteEntries;

    /**
     * sync-able resources in the local collection, as enumerated by {@link #prepareLocal()}
     */
    protected Map<String, LocalResource> localResources;

    public SyncManager(Context context, Account account, AccountSettings settings, Bundle extras, String authority, SyncResult syncResult, String uniqueCollectionId, CollectionInfo.Type serviceType) throws InvalidAccountException {
        this.context = context;
        this.account = account;
        this.settings = settings;
        this.extras = extras;
        this.authority = authority;
        this.syncResult = syncResult;
        this.serviceType = serviceType;

        // create HttpClient with given logger
        httpClient = HttpClient.create(context, account);

        // dismiss previous error notifications
        this.uniqueCollectionId = uniqueCollectionId;
        notificationManager = new NotificationHelper(context, uniqueCollectionId, notificationId());
        notificationManager.cancel();
    }

    protected abstract int notificationId();

    protected abstract String getSyncErrorTitle();

    @TargetApi(21)
    public void performSync() {
        String syncPhase = SYNC_PHASE_PREPARE;
        try {
            App.log.info("Sync phase: " + syncPhase);
            if (!prepare()) {
                App.log.info("No reason to synchronize, aborting");
                return;
            }

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_QUERY_CAPABILITIES;
            App.log.info("Sync phase: " + syncPhase);
            queryCapabilities();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_PREPARE_LOCAL;
            App.log.info("Sync phase: " + syncPhase);
            prepareLocal();

            /* Create journal entries out of local changes. */
            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_CREATE_LOCAL_ENTRIES;
            App.log.info("Sync phase: " + syncPhase);
            createLocalEntries();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_FETCH_ENTRIES;
            App.log.info("Sync phase: " + syncPhase);
            fetchEntries();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_APPLY_REMOTE_ENTRIES;
            App.log.info("Sync phase: " + syncPhase);
            applyRemoteEntries();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_APPLY_LOCAL_ENTRIES;
            App.log.info("Sync phase: " + syncPhase);
            applyLocalEntries();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_PUSH_ENTRIES;
            App.log.info("Sync phase: " + syncPhase);
            pushEntries();

            /* Cleanup and finalize changes */
            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_POST_PROCESSING;
            App.log.info("Sync phase: " + syncPhase);
            postProcess();

            syncPhase = SYNC_PHASE_SAVE_SYNC_TAG;
            App.log.info("Sync phase: " + syncPhase);
            saveSyncTag();

        } catch (IOException e) {
            App.log.log(Level.WARNING, "I/O exception during sync, trying again later", e);
            syncResult.stats.numIoExceptions++;

        } catch (Exceptions.ServiceUnavailableException e) {
            Date retryAfter = null; // ((Exceptions.ServiceUnavailableException) e).retryAfter;
            if (retryAfter != null) {
                // how many seconds to wait? getTime() returns ms, so divide by 1000
                // syncResult.delayUntil = (retryAfter.getTime() - new Date().getTime()) / 1000;
            }
        } catch (Exception | OutOfMemoryError e) {
            if (e instanceof Exceptions.UnauthorizedException) {
                syncResult.stats.numAuthExceptions++;
            } else if (e instanceof Exceptions.HttpException) {
                syncResult.stats.numParseExceptions++;
            } else if (e instanceof CalendarStorageException || e instanceof ContactsStorageException) {
                syncResult.databaseError = true;
            } else if (e instanceof Exceptions.IntegrityException) {
                syncResult.stats.numParseExceptions++;
            } else {
                syncResult.stats.numParseExceptions++;
            }

            notificationManager.setThrowable(e);

            final Intent detailsIntent = notificationManager.getDetailsIntent();
            if (e instanceof Exceptions.UnauthorizedException) {
                detailsIntent.putExtra(AccountSettingsActivity.EXTRA_ACCOUNT, account);
            } else {
                detailsIntent.putExtra(DebugInfoActivity.KEY_ACCOUNT, account);
                detailsIntent.putExtra(DebugInfoActivity.KEY_AUTHORITY, authority);
                detailsIntent.putExtra(DebugInfoActivity.KEY_PHASE, syncPhase);
            }

            notificationManager.notify(getSyncErrorTitle(), syncPhase);
        }
    }


    /**
     * Prepares synchronization (for instance, allocates necessary resources).
     *
     * @return whether actual synchronization is required / can be made. true = synchronization
     * shall be continued, false = synchronization can be skipped
     */
    abstract protected boolean prepare() throws ContactsStorageException, CalendarStorageException;

    abstract protected void processSyncEntry(SyncEntry cEntry) throws IOException, ContactsStorageException, CalendarStorageException, InvalidCalendarException;

    abstract protected void applyLocalEntries() throws IOException, ContactsStorageException, CalendarStorageException, Exceptions.HttpException;

    protected void queryCapabilities() throws IOException, CalendarStorageException, ContactsStorageException {
    }

    protected void fetchEntries() throws Exceptions.HttpException, ContactsStorageException, CalendarStorageException, Exceptions.IntegrityException {
        remoteEntries = journal.getEntries(settings.password(), remoteCTag);

        if (!remoteEntries.isEmpty()) {
            remoteCTag = remoteEntries.get(remoteEntries.size() - 1).getUuid();
        }
    }

    protected void applyRemoteEntries() throws IOException, ContactsStorageException, CalendarStorageException, InvalidCalendarException {
        // Process new vcards from server
        for (JournalEntryManager.Entry entry : remoteEntries) {
            if (Thread.interrupted())
                return;

            App.log.info("Processing " + entry.toString());

            SyncEntry cEntry = SyncEntry.fromJournalEntry(settings.password(), entry);
            App.log.info("Processing resource for journal entry " + entry.getUuid());
            processSyncEntry(cEntry);
        }
    }

    protected void pushEntries() throws Exceptions.HttpException, IOException, ContactsStorageException, CalendarStorageException {
        // upload dirty contacts
        // FIXME: Deal with failure (someone else uploaded before we go here)
        if (!localEntries.isEmpty()) {
            journal.putEntries(localEntries, remoteCTag);

            for (LocalResource local : localCollection.getDirty()) {
                App.log.info("Added/changed resource with UUID: " + local.getUuid());
                local.clearDirty(local.getUuid());
            }

            for (LocalResource local : localCollection.getDeleted()) {
                local.delete();
            }

            remoteCTag = localEntries.get(localEntries.size() - 1).getUuid();
        }
    }

    protected void createLocalEntries() throws CalendarStorageException, ContactsStorageException, IOException {
        localEntries = new LinkedList<>();

        // Not saving, just creating a fake one until we load it from a local db
        JournalEntryManager.Entry previousEntry = (remoteCTag != null) ? JournalEntryManager.Entry.getFakeWithUid(remoteCTag) : null;

        for (LocalResource local : processLocallyDeleted()) {
            SyncEntry entry = new SyncEntry(local.getContent(), SyncEntry.Actions.DELETE);
            JournalEntryManager.Entry tmp = new JournalEntryManager.Entry();
            tmp.update(settings.password(), entry.toJson(), previousEntry);
            previousEntry = tmp;
            localEntries.add(previousEntry);
        }

        for (LocalResource local : localCollection.getDirty()) {
            SyncEntry.Actions action;
            if (local.isLocalOnly()) {
                action = SyncEntry.Actions.ADD;
            } else {
                action = SyncEntry.Actions.CHANGE;
            }

            SyncEntry entry = new SyncEntry(local.getContent(), action);
            JournalEntryManager.Entry tmp = new JournalEntryManager.Entry();
            tmp.update(settings.password(), entry.toJson(), previousEntry);
            previousEntry = tmp;
            localEntries.add(previousEntry);
        }
    }

    /**
     * Lists all local resources which should be taken into account for synchronization into {@link #localResources}.
     */
    protected void prepareLocal() throws CalendarStorageException, ContactsStorageException {
        prepareDirty();

        // fetch list of local contacts and build hash table to index file name
        LocalResource[] localList = localCollection.getAll();
        localResources = new HashMap<>(localList.length);
        for (LocalResource resource : localList) {
            App.log.fine("Found local resource: " + resource.getUuid());
            localResources.put(resource.getUuid(), resource);
        }

        remoteCTag = localCollection.getCTag();
    }


    /**
     * Delete unpublished locally deleted, and return the rest.
     * Checks Thread.interrupted() before each request to allow quick sync cancellation.
     */
    protected List<LocalResource> processLocallyDeleted() throws CalendarStorageException, ContactsStorageException {
        LocalResource[] localList = localCollection.getDeleted();
        List<LocalResource> ret = new ArrayList<>(localList.length);

        for (LocalResource local : localList) {
            if (Thread.interrupted())
                return ret;

            if (!local.isLocalOnly()) {
                App.log.info(local.getUuid() + " has been deleted locally -> deleting from server");
                ret.add(local);
            } else {
                App.log.info("Removing local record #" + local.getId() + " which has been deleted locally and was never uploaded");
                local.delete();
            }

            syncResult.stats.numDeletes++;
        }

        return ret;
    }

    protected void prepareDirty() throws CalendarStorageException, ContactsStorageException {
        // assign file names and UIDs to new contacts so that we can use the file name as an index
        App.log.info("Looking for contacts/groups without file name");
        for (LocalResource local : localCollection.getWithoutFileName()) {
            String uuid = UUID.randomUUID().toString();
            App.log.fine("Found local record #" + local.getId() + " without file name; assigning file name/UID based on " + uuid);
            local.updateFileNameAndUID(uuid);
        }
    }

    /**
     * For post-processing of entries, for instance assigning groups.
     */
    protected void postProcess() throws CalendarStorageException, ContactsStorageException {
    }

    protected void saveSyncTag() throws CalendarStorageException, ContactsStorageException {
        App.log.info("Saving CTag=" + remoteCTag);
        localCollection.setCTag(remoteCTag);
    }


    static class SyncEntry {
        @Getter
        private String content;
        @Getter
        private Actions action;

        enum Actions {
            ADD("ADD"),
            CHANGE("CHANGE"),
            DELETE("DELETE");

            private final String text;

            Actions(final String text) {
                this.text = text;
            }

            @Override
            public String toString() {
                return text;
            }
        }

        @SuppressWarnings("unused")
        private SyncEntry() {
        }

        protected SyncEntry(String content, Actions action) {
            this.content = content;
            this.action = action;
        }

        boolean isAction(Actions action) {
            return this.action.equals(action);
        }

        static SyncEntry fromJournalEntry(String keyBase64, JournalEntryManager.Entry entry) {
            return GsonHelper.gson.fromJson(entry.getContent(keyBase64), SyncEntry.class);
        }

        String toJson() {
            return GsonHelper.gson.toJson(this, this.getClass());
        }
    }
}
