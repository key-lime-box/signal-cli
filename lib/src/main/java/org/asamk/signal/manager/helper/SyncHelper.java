package org.asamk.signal.manager.helper;

import org.asamk.signal.manager.api.Contact;
import org.asamk.signal.manager.api.GroupId;
import org.asamk.signal.manager.api.TrustLevel;
import org.asamk.signal.manager.storage.SignalAccount;
import org.asamk.signal.manager.storage.groups.GroupInfoV1;
import org.asamk.signal.manager.storage.recipients.RecipientAddress;
import org.asamk.signal.manager.storage.stickers.StickerPack;
import org.asamk.signal.manager.util.AttachmentUtils;
import org.asamk.signal.manager.util.IOUtils;
import org.asamk.signal.manager.util.MimeUtils;
import org.signal.libsignal.protocol.IdentityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ConfigurationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ContactsMessage;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContact;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsOutputStream;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceGroup;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceGroupsInputStream;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceGroupsOutputStream;
import org.whispersystems.signalservice.api.messages.multidevice.KeysMessage;
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SyncMessage;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncHelper {

    private static final Logger logger = LoggerFactory.getLogger(SyncHelper.class);

    private final Context context;
    private final SignalAccount account;

    public SyncHelper(final Context context) {
        this.context = context;
        this.account = context.getAccount();
    }

    public void requestAllSyncData() {
        requestSyncData(SyncMessage.Request.Type.GROUPS);
        requestSyncData(SyncMessage.Request.Type.CONTACTS);
        requestSyncData(SyncMessage.Request.Type.BLOCKED);
        requestSyncData(SyncMessage.Request.Type.CONFIGURATION);
        requestSyncKeys();
        requestSyncPniIdentity();
    }

    public void requestSyncKeys() {
        requestSyncData(SyncMessage.Request.Type.KEYS);
    }

    public void requestSyncPniIdentity() {
        requestSyncData(SyncMessage.Request.Type.PNI_IDENTITY);
    }

    public SendMessageResult sendSyncFetchProfileMessage() {
        return context.getSendHelper()
                .sendSyncMessage(SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.LOCAL_PROFILE));
    }

    public void sendSyncFetchStorageMessage() {
        context.getSendHelper()
                .sendSyncMessage(SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.STORAGE_MANIFEST));
    }

    public void sendGroups() throws IOException {
        var groupsFile = IOUtils.createTempFile();

        try {
            try (OutputStream fos = new FileOutputStream(groupsFile)) {
                var out = new DeviceGroupsOutputStream(fos);
                for (var record : account.getGroupStore().getGroups()) {
                    if (record instanceof GroupInfoV1 groupInfo) {
                        out.write(new DeviceGroup(groupInfo.getGroupId().serialize(),
                                Optional.ofNullable(groupInfo.name),
                                groupInfo.getMembers()
                                        .stream()
                                        .map(context.getRecipientHelper()::resolveSignalServiceAddress)
                                        .toList(),
                                context.getGroupHelper().createGroupAvatarAttachment(groupInfo.getGroupId()),
                                groupInfo.isMember(account.getSelfRecipientId()),
                                Optional.of(groupInfo.messageExpirationTime),
                                Optional.ofNullable(groupInfo.color),
                                groupInfo.blocked,
                                Optional.empty(),
                                groupInfo.archived));
                    }
                }
            }

            if (groupsFile.exists() && groupsFile.length() > 0) {
                try (var groupsFileStream = new FileInputStream(groupsFile)) {
                    var attachmentStream = SignalServiceAttachment.newStreamBuilder()
                            .withStream(groupsFileStream)
                            .withContentType(MimeUtils.OCTET_STREAM)
                            .withLength(groupsFile.length())
                            .build();

                    context.getSendHelper().sendSyncMessage(SignalServiceSyncMessage.forGroups(attachmentStream));
                }
            }
        } finally {
            try {
                Files.delete(groupsFile.toPath());
            } catch (IOException e) {
                logger.warn("Failed to delete groups temp file “{}”, ignoring: {}", groupsFile, e.getMessage());
            }
        }
    }

    public void sendContacts() throws IOException {
        var contactsFile = IOUtils.createTempFile();

        try {
            try (OutputStream fos = new FileOutputStream(contactsFile)) {
                var out = new DeviceContactsOutputStream(fos);
                for (var contactPair : account.getContactStore().getContacts()) {
                    final var recipientId = contactPair.first();
                    final var contact = contactPair.second();
                    final var address = context.getRecipientHelper().resolveSignalServiceAddress(recipientId);

                    var currentIdentity = account.getIdentityKeyStore().getIdentityInfo(address.getServiceId());
                    VerifiedMessage verifiedMessage = null;
                    if (currentIdentity != null) {
                        verifiedMessage = new VerifiedMessage(address,
                                currentIdentity.getIdentityKey(),
                                currentIdentity.getTrustLevel().toVerifiedState(),
                                currentIdentity.getDateAddedTimestamp());
                    }

                    var profileKey = account.getProfileStore().getProfileKey(recipientId);
                    out.write(new DeviceContact(address,
                            Optional.ofNullable(contact.getName()),
                            createContactAvatarAttachment(new RecipientAddress(address)),
                            Optional.ofNullable(contact.color()),
                            Optional.ofNullable(verifiedMessage),
                            Optional.ofNullable(profileKey),
                            contact.isBlocked(),
                            Optional.of(contact.messageExpirationTime()),
                            Optional.empty(),
                            contact.isArchived()));
                }

                if (account.getProfileKey() != null) {
                    // Send our own profile key as well
                    out.write(new DeviceContact(account.getSelfAddress(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(account.getProfileKey()),
                            false,
                            Optional.empty(),
                            Optional.empty(),
                            false));
                }
            }

            if (contactsFile.exists() && contactsFile.length() > 0) {
                try (var contactsFileStream = new FileInputStream(contactsFile)) {
                    var attachmentStream = SignalServiceAttachment.newStreamBuilder()
                            .withStream(contactsFileStream)
                            .withContentType(MimeUtils.OCTET_STREAM)
                            .withLength(contactsFile.length())
                            .build();

                    context.getSendHelper()
                            .sendSyncMessage(SignalServiceSyncMessage.forContacts(new ContactsMessage(attachmentStream,
                                    true)));
                }
            }
        } finally {
            try {
                Files.delete(contactsFile.toPath());
            } catch (IOException e) {
                logger.warn("Failed to delete contacts temp file “{}”, ignoring: {}", contactsFile, e.getMessage());
            }
        }
    }

    public SendMessageResult sendBlockedList() {
        var addresses = new ArrayList<SignalServiceAddress>();
        for (var record : account.getContactStore().getContacts()) {
            if (record.second().isBlocked()) {
                addresses.add(context.getRecipientHelper().resolveSignalServiceAddress(record.first()));
            }
        }
        var groupIds = new ArrayList<byte[]>();
        for (var record : account.getGroupStore().getGroups()) {
            if (record.isBlocked()) {
                groupIds.add(record.getGroupId().serialize());
            }
        }
        return context.getSendHelper()
                .sendSyncMessage(SignalServiceSyncMessage.forBlocked(new BlockedListMessage(addresses, groupIds)));
    }

    public SendMessageResult sendVerifiedMessage(
            SignalServiceAddress destination, IdentityKey identityKey, TrustLevel trustLevel
    ) {
        var verifiedMessage = new VerifiedMessage(destination,
                identityKey,
                trustLevel.toVerifiedState(),
                System.currentTimeMillis());
        return context.getSendHelper().sendSyncMessage(SignalServiceSyncMessage.forVerified(verifiedMessage));
    }

    public SendMessageResult sendKeysMessage() {
        var keysMessage = new KeysMessage(Optional.ofNullable(account.getOrCreateStorageKey()),
                Optional.ofNullable(account.getOrCreatePinMasterKey()));
        return context.getSendHelper().sendSyncMessage(SignalServiceSyncMessage.forKeys(keysMessage));
    }

    public SendMessageResult sendStickerOperationsMessage(
            List<StickerPack> installStickers, List<StickerPack> removeStickers
    ) {
        var installStickerMessages = installStickers.stream().map(s -> getStickerPackOperationMessage(s, true));
        var removeStickerMessages = removeStickers.stream().map(s -> getStickerPackOperationMessage(s, false));
        var stickerMessages = Stream.concat(installStickerMessages, removeStickerMessages).toList();
        return context.getSendHelper()
                .sendSyncMessage(SignalServiceSyncMessage.forStickerPackOperations(stickerMessages));
    }

    private static StickerPackOperationMessage getStickerPackOperationMessage(
            final StickerPack s, final boolean installed
    ) {
        return new StickerPackOperationMessage(s.packId().serialize(),
                s.packKey(),
                installed ? StickerPackOperationMessage.Type.INSTALL : StickerPackOperationMessage.Type.REMOVE);
    }

    public SendMessageResult sendConfigurationMessage() {
        final var config = account.getConfigurationStore();
        var configurationMessage = new ConfigurationMessage(Optional.ofNullable(config.getReadReceipts()),
                Optional.ofNullable(config.getUnidentifiedDeliveryIndicators()),
                Optional.ofNullable(config.getTypingIndicators()),
                Optional.ofNullable(config.getLinkPreviews()));
        return context.getSendHelper().sendSyncMessage(SignalServiceSyncMessage.forConfiguration(configurationMessage));
    }

    public void handleSyncDeviceGroups(final InputStream input) {
        final var s = new DeviceGroupsInputStream(input);
        DeviceGroup g;
        while (true) {
            try {
                g = s.read();
            } catch (IOException e) {
                logger.warn("Sync groups contained invalid group, ignoring: {}", e.getMessage());
                continue;
            }
            if (g == null) {
                break;
            }
            var syncGroup = account.getGroupStore().getOrCreateGroupV1(GroupId.v1(g.getId()));
            if (syncGroup != null) {
                if (g.getName().isPresent()) {
                    syncGroup.name = g.getName().get();
                }
                syncGroup.addMembers(g.getMembers()
                        .stream()
                        .map(account.getRecipientResolver()::resolveRecipient)
                        .collect(Collectors.toSet()));
                if (!g.isActive()) {
                    syncGroup.removeMember(account.getSelfRecipientId());
                } else {
                    // Add ourself to the member set as it's marked as active
                    syncGroup.addMembers(List.of(account.getSelfRecipientId()));
                }
                syncGroup.blocked = g.isBlocked();
                if (g.getColor().isPresent()) {
                    syncGroup.color = g.getColor().get();
                }

                if (g.getAvatar().isPresent()) {
                    context.getGroupHelper().downloadGroupAvatar(syncGroup.getGroupId(), g.getAvatar().get());
                }
                syncGroup.archived = g.isArchived();
                account.getGroupStore().updateGroup(syncGroup);
            }
        }
    }

    public void handleSyncDeviceContacts(final InputStream input) throws IOException {
        final var s = new DeviceContactsInputStream(input);
        DeviceContact c;
        while (true) {
            try {
                c = s.read();
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("Missing contact address!")) {
                    logger.warn("Sync contacts contained invalid contact, ignoring: {}", e.getMessage());
                    continue;
                } else {
                    throw e;
                }
            }
            if (c == null) {
                break;
            }
            if (c.getAddress().matches(account.getSelfAddress()) && c.getProfileKey().isPresent()) {
                account.setProfileKey(c.getProfileKey().get());
            }
            final var recipientId = account.getRecipientTrustedResolver().resolveRecipientTrusted(c.getAddress());
            var contact = account.getContactStore().getContact(recipientId);
            final var builder = contact == null ? Contact.newBuilder() : Contact.newBuilder(contact);
            if (c.getName().isPresent() && (
                    contact == null || (
                            contact.givenName() == null
                                    && contact.familyName() == null
                    )
            )) {
                builder.withGivenName(c.getName().get());
                builder.withFamilyName(null);
            }
            if (c.getColor().isPresent()) {
                builder.withColor(c.getColor().get());
            }
            if (c.getProfileKey().isPresent()) {
                account.getProfileStore().storeProfileKey(recipientId, c.getProfileKey().get());
            }
            if (c.getVerified().isPresent()) {
                final var verifiedMessage = c.getVerified().get();
                account.getIdentityKeyStore()
                        .setIdentityTrustLevel(verifiedMessage.getDestination().getServiceId(),
                                verifiedMessage.getIdentityKey(),
                                TrustLevel.fromVerifiedState(verifiedMessage.getVerified()));
            }
            if (c.getExpirationTimer().isPresent()) {
                builder.withMessageExpirationTime(c.getExpirationTimer().get());
            }
            builder.withIsBlocked(c.isBlocked());
            builder.withIsArchived(c.isArchived());
            account.getContactStore().storeContact(recipientId, builder.build());

            if (c.getAvatar().isPresent()) {
                downloadContactAvatar(c.getAvatar().get(), new RecipientAddress(c.getAddress()));
            }
        }
    }

    private SendMessageResult requestSyncData(final SyncMessage.Request.Type type) {
        var r = new SyncMessage.Request.Builder().type(type).build();
        var message = SignalServiceSyncMessage.forRequest(new RequestMessage(r));
        return context.getSendHelper().sendSyncMessage(message);
    }

    private Optional<SignalServiceAttachmentStream> createContactAvatarAttachment(RecipientAddress address) throws IOException {
        final var streamDetails = context.getAvatarStore().retrieveContactAvatar(address);
        if (streamDetails == null) {
            return Optional.empty();
        }

        return Optional.of(AttachmentUtils.createAttachmentStream(streamDetails, Optional.empty()));
    }

    private void downloadContactAvatar(SignalServiceAttachment avatar, RecipientAddress address) {
        try {
            context.getAvatarStore()
                    .storeContactAvatar(address,
                            outputStream -> context.getAttachmentHelper().retrieveAttachment(avatar, outputStream));
        } catch (IOException e) {
            logger.warn("Failed to download avatar for contact {}, ignoring: {}", address, e.getMessage());
        }
    }
}
