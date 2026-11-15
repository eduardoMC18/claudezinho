package com.estudotron.service;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.Optional;
import java.util.prefs.Preferences;

/** Seletor de arquivos PDF; lembra a última pasta usada. */
public final class FileDialogService {

    private static final String PREF_LAST_DIR = "lastPdfDir";

    private final Preferences prefs = Preferences.userNodeForPackage(FileDialogService.class);

    public Optional<File> choosePdf(Window owner, String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Arquivos PDF", "*.pdf"));
        File lastDir = new File(prefs.get(PREF_LAST_DIR, ""));
        if (lastDir.isDirectory()) {
            chooser.setInitialDirectory(lastDir);
        }
        File file = chooser.showOpenDialog(owner);
        if (file != null && file.getParentFile() != null) {
            prefs.put(PREF_LAST_DIR, file.getParentFile().getAbsolutePath());
        }
        return Optional.ofNullable(file);
    }
}
