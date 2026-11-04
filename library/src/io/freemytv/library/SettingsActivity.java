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
    private static int keepRow = -1;      // the row the remote was on, kept over a redraw (and over a new window after a theme change)
    private TextView result;

    private String look = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        look = Ui.refresh();
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), Ui.dp(this, 40));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.addView(new Backdrop(this), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(stage);
    }

    @Override protected void onStart() {
        super.onStart();
        render();
    }

    private void render() {
        final int row = keepRow;
        keepRow = -1;
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
        row("Тема", "light".equals(Prefs.theme()) ? "светлая" : "black".equals(Prefs.theme()) ? "чёрная" : "тёмная", new Runnable() {
            @Override public void run() {
                String t = Prefs.theme();
                Prefs.setTheme("dark".equals(t) ? "light" : "light".equals(t) ? "black" : "dark");
                recreate();
            }
        });
        row("Фон", sceneName(Prefs.scene()), new Runnable() {
            @Override public void run() {
                String[] all = {"film", "aurora", "mountains", "dunes", "ocean", "plain"};
                String cur = Prefs.scene();
                String next = all[0];
                for (int i = 0; i < all.length; i++) if (all[i].equals(cur)) { next = all[(i + 1) % all.length]; break; }
                Prefs.setScene(next);
                recreate();
            }
        });
        row("Озвучка по умолчанию", "ru".equals(Prefs.audio()) ? "русская, если есть" : "как в файле", new Runnable() {
            @Override public void run() {
                Prefs.setAudio("ru".equals(Prefs.audio()) ? "orig" : "ru");
                render();
            }
        });
        row("История просмотра на главной", Prefs.showHistory() ? "показывать" : "скрыта", new Runnable() {
            @Override public void run() {
                Prefs.setShowHistory(!Prefs.showHistory());
                render();
            }
        });
        row("Очистить историю просмотра", "", new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(SettingsActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Очистить историю?")
                        .setMessage("Пропадёт «Продолжить просмотр». Отметки «просмотрено» останутся.")
                        .setPositiveButton("Очистить", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                lib.catalog.clearHistory();
                                lib.refreshView();
                                result.setText("История очищена.");
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
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
        row("Буфер чтения (тяжёлые файлы)", Prefs.buffer() == 0 ? "авто" : Prefs.buffer() + " с", new Runnable() {
            @Override public void run() {
                int[] steps = {0, 4, 8, 15, 30, 60};
                int next = steps[0];
                for (int i = 0; i < steps.length; i++) if (steps[i] == Prefs.buffer()) { next = steps[(i + 1) % steps.length]; break; }
                Prefs.setBuffer(next);
                render();
            }
        });
        row("Декодирование видео", "hw".equals(Prefs.decoder()) ? "аппаратное, принудительно" : "sw".equals(Prefs.decoder()) ? "программное" : "lite".equals(Prefs.decoder()) ? "программное, облегчённое (для VC-1 и тяжёлых файлов)" : "авто", new Runnable() {
            @Override public void run() {
                String d = Prefs.decoder();
                Prefs.setDecoder("auto".equals(d) ? "hw" : "hw".equals(d) ? "sw" : "sw".equals(d) ? "lite" : "auto");
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
        if (row >= 0 && row < page.getChildCount()) {
            final View target = page.getChildAt(row);
            target.post(new Runnable() { @Override public void run() { target.requestFocus(); } });
        }
    }

    private static String sceneName(String s) {
        switch (s) {
            case "aurora": return "северное сияние";
            case "mountains": return "горы";
            case "dunes": return "дюны";
            case "ocean": return "море";
            case "plain": return "однотонный";
            default: return "картинка выбранного фильма";
        }
    }

    private interface Value {
        void set(String v);
    }

    private void row(String label, String value, final Runnable action) {
        TextView t = Ui.button(this, value.isEmpty() ? label : label + "   ·   " + value);
        t.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                keepRow = page.indexOfChild(v);
                action.run();
            }
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
