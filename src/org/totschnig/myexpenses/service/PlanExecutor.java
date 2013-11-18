package org.totschnig.myexpenses.service;

import java.util.Date;

import org.totschnig.myexpenses.MyApplication;
import org.totschnig.myexpenses.R;
import org.totschnig.myexpenses.activity.ExpenseEdit;
import org.totschnig.myexpenses.activity.MyExpenses;
import org.totschnig.myexpenses.model.Account;
import org.totschnig.myexpenses.model.Template;
import org.totschnig.myexpenses.model.Transaction;
import org.totschnig.myexpenses.preference.SharedPreferencesCompat;
import org.totschnig.myexpenses.provider.DatabaseConstants;
import org.totschnig.myexpenses.util.Utils;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class PlanExecutor extends IntentService {

  public PlanExecutor() {
    super("PlanExexcutor");

  }

  @SuppressLint("NewApi")
  @Override
  public void onHandleIntent(Intent intent) {
    Log.i("DEBUG","Inside plan executor onHandleIntent");
    MyApplication app = MyApplication.getInstance();
    app.requirePlaner();
    long lastExecutionTimeStamp = app.getSettings().getLong(
        MyApplication.PREFKEY_PLANER_LAST_EXECUTION_TIMESTAMP, 0);
    long now = System.currentTimeMillis();
    if (lastExecutionTimeStamp == 0) {
      Log.i("DEBUG", "first call, nothting to do");
    } else {
      Log.i("DEBUG", String.format(
          "executing plans from %s to %s",
          new Date(lastExecutionTimeStamp).toString(),
          new Date(now).toString()));
      String[] INSTANCE_PROJECTION = new String[] {
          Instances.EVENT_ID,
          Instances._ID
        };
      Uri.Builder eventsUriBuilder = CalendarContract.Instances.CONTENT_URI
          .buildUpon();
      ContentUris.appendId(eventsUriBuilder, lastExecutionTimeStamp);
      ContentUris.appendId(eventsUriBuilder, now);
      Uri eventsUri = eventsUriBuilder.build();
      //Instances.Content_URI returns events that fall totally or partially in a given range
      //we additionally select only instances where the begin is inside the range
      //because we want to deal with each instance only once
      Cursor cursor = getContentResolver().query(eventsUri, INSTANCE_PROJECTION,
          Events.CALENDAR_ID + " = ? AND "+ Instances.BEGIN + " BETWEEN ? AND ?",
          new String[]{
            String.valueOf(MyApplication.getInstance().planerCalenderId),
            String.valueOf(lastExecutionTimeStamp),
            String.valueOf(now)}, 
            null);
      if (cursor.moveToFirst()) {
        while (cursor.isAfterLast() == false) {
          long planId = cursor.getLong(0);
          long instanceId = cursor.getLong(1);
          //2) check if they are part of a plan linked to a template
          //3) execute the template
          Log.i("DEBUG",String.format("found instance %d of plan %d",instanceId,planId));
          Template template = Template.getInstanceForPlan(planId);
          //TODO handle automatic and manual execution
          if (template != null) {
            PendingIntent resultIntent;
            Account account = Account.getInstanceFromDb(template.accountId);
            NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String content = template.label;
            if (!content.equals(""))
              content += " : ";
            content += Utils.formatCurrency(template.amount);
            NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(account.label + " : " + template.title)
                .setContentText(content);
            if (template.planExecutionAutomatic) {
              Uri uri = Transaction.getInstanceFromTemplate(template).save();
              long id = ContentUris.parseId(uri);
              Intent displayIntent = new Intent(this, MyExpenses.class);
              displayIntent.putExtra(DatabaseConstants.KEY_ROWID, template.accountId);
              displayIntent.putExtra("transaction_id", id);
              resultIntent = PendingIntent.getActivity(this, 0, displayIntent, 0);
            } else {
              Intent editIntent = new Intent(this,ExpenseEdit.class);
              editIntent.putExtra("template_id", template.id);
              editIntent.putExtra("instantiate", true);
              resultIntent = PendingIntent.getActivity(this, 0, editIntent, 0);
              mBuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", null);
              mBuilder.addAction(android.R.drawable.ic_menu_edit, "Edit",resultIntent);
              mBuilder.addAction(android.R.drawable.ic_menu_save, "Apply", null);
            }
            mBuilder.setContentIntent(resultIntent);
            mBuilder.setAutoCancel(true);  
            mNotificationManager.notify(0, mBuilder.build());
          }
          cursor.moveToNext();
        }
      }
      cursor.close();
    }
    SharedPreferencesCompat.apply(app.getSettings().edit()
        .putLong(MyApplication.PREFKEY_PLANER_LAST_EXECUTION_TIMESTAMP, now));
  }


}