package io.freemytv.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;

/** Asks, then deletes a file or a folder from the disk itself. There is no way back, so it asks plainly. */
final class Deleter {
    private Deleter() {}

    static void ask(final Activity a, final Library lib, final String path, boolean dir, final Runnable after) {
        int i = path.lastIndexOf('/');
        String name = i < 0 ? path : path.substring(i + 1);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Удалить с диска?")
                .setMessage(name + (dir ? "\n\nПапка будет удалена вместе со всем, что в ней лежит." : "")
                        + "\n\nФайл исчезнет с диска насовсем, вернуть его нельзя.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Toast.makeText(a, "Удаляю…", Toast.LENGTH_SHORT).show();
                        lib.deleteFromDisk(path, new Library.Done() {
                            @Override public void on(String error) {
                                if (error != null) {
                                    new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                            .setTitle("Не удалось удалить").setMessage(error).setPositiveButton("OK", null).show();
                                } else {
                                    Toast.makeText(a, "Удалено", Toast.LENGTH_SHORT).show();
                                    after.run();
                                }
                            }
                        });
                    }
                })
                .show();
    }
}
