package com.droid.videoRecorder;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * Created by Robson on 03/02/2016.
 */

public class DroidNotification extends DroidBaseNotification {

    private String lastCommand = "";
    private long lastCommandTime;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String msgNotification = getCommandFromNotification(sbn);
        Log.d("DVR", "Comando por notificacao detectado: " + msgNotification);

        if (!msgNotification.isEmpty()) {
            long now = System.currentTimeMillis();
            if (!msgNotification.equals(lastCommand) || now - lastCommandTime > 2000) {
                SendBroadCast(msgNotification);
                lastCommand = msgNotification;
                lastCommandTime = now;
            } else {
                Log.d("DVR", "Comando por notificacao ignorado por duplicidade: " + msgNotification);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        lastCommand = "";
        lastCommandTime = 0;
    }

    private void SendBroadCast(String msgNotification) {
        Intent mIntent = new Intent();
        mIntent.setAction(DroidConstants.CHAVERECEIVER);
        mIntent.setComponent(new ComponentName(this, DroidReceiver.class));
        mIntent.addCategory(Intent.CATEGORY_DEFAULT);
        mIntent.putExtra(DroidConstants.CHAVERECEIVER, msgNotification);
        sendBroadcast(mIntent);
    }

    private String getCommandFromNotification(StatusBarNotification mStatusBarNotification) {
        Bundle extras = mStatusBarNotification.getNotification().extras;
        StringBuilder notificationText = new StringBuilder();

        append(notificationText, extras.getCharSequence(Notification.EXTRA_TITLE));
        append(notificationText, extras.getCharSequence(Notification.EXTRA_TEXT));
        append(notificationText, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        append(notificationText, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));

        CharSequence[] descArray = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (descArray != null) {
            for (CharSequence line : descArray) {
                append(notificationText, line);
            }
        }

        return findLastCommand(notificationText.toString());
    }

    private void append(StringBuilder builder, CharSequence value) {
        if (value != null) {
            builder.append(' ').append(value);
        }
    }

    private String findLastCommand(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.toUpperCase(Locale.US)
                .replace('\n', ' ')
                .replace('\r', ' ');
        String[] commands = {"MIR", "CFG", "MI", "MV", "D", "R", "S", "V", "C", "Q"};
        String lastCommandFound = "";
        int lastCommandIndex = -1;

        for (String command : commands) {
            int commandIndex = Math.max(
                    normalized.lastIndexOf("DVR=" + command),
                    normalized.lastIndexOf("DVR" + command));

            if (commandIndex > lastCommandIndex) {
                lastCommandIndex = commandIndex;
                lastCommandFound = command;
            }
        }

        if (lastCommandFound.isEmpty()) {
            return "";
        }

        return DroidConstants.COMANDOINICIADOPOR + lastCommandFound;
    }

}
