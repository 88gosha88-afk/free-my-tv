package io.freemytv.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.freemytv.library.core.Node;

import java.io.IOException;
import java.util.List;

/** The network folder, the key for descriptions, the soundtrack choice and a connection check. */
public class SettingsActivity extends Activity {
    private Library lib;
    private LinearLayout page;
    private TextView result;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), Ui.dp(this, 40));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override protected void onStart() {
        super.onStart();
        render();
    }

    private void render() {
        page.removeAllViews();
        page.addView(Ui.bold(this, "Настройки", 28, Ui.TEXT));
        row("Адрес диска", Prefs.smbUrl(), new Runnable() {
            @Override public void run() {
                ask("Адрес диска", "smb://192.168.1.1/Имя папки/", Prefs.smbUrl(), false, new Value() {
                    @Override public void set(String v) { Prefs.setSmbUrl(v); }
                });
            }
        });
        row("Логин", Prefs.user().isEmpty() ? "гость (без пароля)" : Prefs.user(), new Runnable() {
            @Override public void run() {
                ask("Логин (пусто — гостевой доступ)", "", Prefs.user(), false, new Value() {
                    @Override public void set(String v) { Prefs.setUser(v); }
                });
            }
        });
        row("Пароль", Prefs.password().isEmpty() ? "не задан" : "••••••••", new Runnable() {
            @Override public void run() {
                ask("Пароль", "", Prefs.password(), true, new Value() {
                    @Override public void set(String v) { Prefs.setPassword(v); }
                });
            }
        });
        row("Ключ TMDB (описания и постеры)", Prefs.tmdbKey().isEmpty() ? "не задан" : "задан", new Runnable() {
            @Override public void run() {
                ask("Ключ TMDB (бесплатный, themoviedb.org → Настройки → API)", "", Prefs.tmdbKey(), false, new Value() {
                    @Override public void set(String v) {
                        Prefs.setTmdbKey(v);
                        lib.lookUpMissing();
                    }
                });
            }
        });
        row("Озвучка по умолчанию", "ru".equals(Prefs.audio()) ? "русская, если есть" : "как в файле", new Runnable() {
            @Override public void run() {
                Prefs.setAudio("ru".equals(Prefs.audio()) ? "orig" : "ru");
                render();
            }
        });
        row("Проигрыватель", Prefs.builtin() ? "встроенный" : "внешний (VLC)", new Runnable() {
            @Override public void run() {
                Prefs.setBuiltin(!Prefs.builtin());
                render();
            }
        });
        row("Как читать файл с диска", Prefs.direct() ? "напрямую по SMB" : "через сервер приложения", new Runnable() {
            @Override public void run() {
                Prefs.setDirect(!Prefs.direct());
                render();
            }
        });
        row("Проверить подключение к диску", "", new Runnable() {
            @Override public void run() { check(); }
        });
        row("Заново просмотреть папки", "", new Runnable() {
            @Override public void run() {
                lib.rescan();
                result.setText("Идёт поиск. Результат на главном экране.");
            }
        });
        result = Ui.text(this, "", 16, Ui.DIM);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(this, 20);
        page.addView(result, rl);
    }

    private interface Value {
        void set(String v);
    }

    private void row(String label, String value, final Runnable action) {
        TextView t = Ui.button(this, value.isEmpty() ? label : label + "   ·   " + value);
        t.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        page.addView(t, lp);
    }

    private void ask(String title, String hint, String current, boolean secret, final Value out) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(current);
        field.setHint(hint);
        field.setSelection(field.getText().length());
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        out.set(field.getText().toString());
                        render();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void check() {
        result.setTextColor(Ui.DIM);
        result.setText("Проверяю…");
        new Thread(new Runnable() {
            @Override public void run() {
                String text;
                int color = Ui.ACCENT;
                try {
                    List<Node> list = lib.source().list("");
                    int dirs = 0, files = 0;
                    for (Node n : list) if (n.dir) dirs++; else files++;
                    text = "Диск отвечает. В корне папок: " + dirs + ", файлов: " + files + ".";
                } catch (IOException | RuntimeException e) {
                    color = Ui.WARN;
                    text = "Не получилось: " + (e.getMessage() != null ? e.getMessage() : e.toString())
                            + "\nПроверьте адрес, логин и пароль; телефон и приставка должны быть в той же сети, что и роутер.";
                }
                final String shown = text;
                final int c = color;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        result.setTextColor(c);
                        result.setText(shown);
                    }
                });
            }
        }).start();
    }
}
