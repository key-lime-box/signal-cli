package org.asamk.signal.commands;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.IOErrorException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.InvalidUsernameException;
import org.asamk.signal.output.JsonWriter;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.output.PlainTextWriter;

import java.io.IOException;

public class UpdateAccountCommand implements JsonRpcLocalCommand {

    @Override
    public String getName() {
        return "updateAccount";
    }

    @Override
    public void attachToSubparser(final Subparser subparser) {
        subparser.help("Update the account attributes on the signal server.");
        subparser.addArgument("-n", "--device-name").help("Specify a name to describe this device.");
        subparser.addArgument("--unrestricted-unidentified-sender")
                .type(Boolean.class)
                .help("Enable if anyone should be able to send you unidentified sender messages.");

        var mut = subparser.addMutuallyExclusiveGroup();
        mut.addArgument("-u", "--username").help("Specify a username that can then be used to contact this account.");
        mut.addArgument("--delete-username")
                .action(Arguments.storeTrue())
                .help("Delete the username associated with this account.");
    }

    @Override
    public void handleCommand(
            final Namespace ns, final Manager m, final OutputWriter outputWriter
    ) throws CommandException {
        final var deviceName = ns.getString("device-name");
        final var unrestrictedUnidentifiedSender = ns.getBoolean("unrestricted-unidentified-sender");
        try {
            m.updateAccountAttributes(deviceName, unrestrictedUnidentifiedSender);
        } catch (IOException e) {
            throw new IOErrorException("UpdateAccount error: " + e.getMessage(), e);
        }

        final var username = ns.getString("username");
        if (username != null) {
            try {
                m.setUsername(username);
                final var newUsername = m.getUsername();
                final var newUsernameLink = m.getUsernameLink();
                switch (outputWriter) {
                    case PlainTextWriter w -> w.println("Your new username: {} ({})",
                            newUsername,
                            newUsernameLink == null ? "-" : newUsernameLink.getUrl());
                    case JsonWriter w -> w.write(new JsonAccountResponse(newUsername,
                            newUsernameLink == null ? null : newUsernameLink.getUrl()));
                }
            } catch (IOException e) {
                throw new IOErrorException("Failed to set username: " + e.getMessage(), e);
            } catch (InvalidUsernameException e) {
                throw new UserErrorException("Invalid username: " + e.getMessage(), e);
            }
        }

        final var deleteUsername = Boolean.TRUE.equals(ns.getBoolean("delete-username"));
        if (deleteUsername) {
            try {
                m.deleteUsername();
            } catch (IOException e) {
                throw new IOErrorException("Failed to delete username: " + e.getMessage(), e);
            }
        }
    }

    private record JsonAccountResponse(
            @JsonInclude(JsonInclude.Include.NON_NULL) String username,
            @JsonInclude(JsonInclude.Include.NON_NULL) String usernameLink
    ) {}
}
