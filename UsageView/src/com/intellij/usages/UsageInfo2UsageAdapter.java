package com.intellij.usages;

import com.intellij.ide.SelectInEditorManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.rules.*;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Dec 17, 2004
 * Time: 2:42:29 PM
 * To change this template use File | Settings | File Templates.
 */
public class UsageInfo2UsageAdapter implements Usage, UsageInModule, UsageInLibrary, UsageInFile, PsiElementUsage, MergeableUsage {
  private static final Logger LOG = Logger.getInstance("#com.intellij.usages.UsageInfo2UsageAdapter");

  private final UsageInfo myUsageInfo;
  private int myLineNumber;
  private int myColumnNumber;
  private PsiFile myPsiFile;
  protected Icon myIcon;
  private List<RangeMarker> myRangeMarkers = new ArrayList<RangeMarker>();
  private TextChunk[] myTextChunks;
  private Document myDocument;

  public UsageInfo2UsageAdapter(final UsageInfo usageInfo) {
    myUsageInfo = usageInfo;
    PsiElement element = myUsageInfo.getElement();
    myPsiFile = element.getContainingFile();
    Project project = element.getProject();
    myDocument = PsiDocumentManager.getInstance(project).getDocument(myPsiFile);

    final PsiElement psiElement = usageInfo.getElement();

    TextRange range = element.getTextRange();
    int startOffset = range.getStartOffset() + usageInfo.startOffset;
    int endOffset = range.getStartOffset() + usageInfo.endOffset;

    myLineNumber = myDocument.getLineNumber(startOffset);

    int lineStartOffset = myDocument.getLineStartOffset(myLineNumber);
    myColumnNumber = startOffset - lineStartOffset;

    if (endOffset > myDocument.getTextLength()) {
      LOG.assertTrue(false,
        "Invalid usage info, psiElement:" + psiElement + " end offset: " + endOffset + " psiFile: " + myPsiFile.getName()
      );
    }

    myRangeMarkers.add(myDocument.createRangeMarker(startOffset, endOffset));

    if (element instanceof PsiFile) {
      myIcon = null;
    }
    else {
      myIcon = element.getIcon(0);
    }

    initChunks();
  }

  private void initChunks() {
    myTextChunks = new ChunkExtractor(myDocument, myLineNumber, myPsiFile, myRangeMarkers, myColumnNumber).extractChunks();
  }

  public UsagePresentation getPresentation() {
    return new UsagePresentation() {
      public TextChunk[] getText() {
        return myTextChunks;
      }

      public Icon getIcon() {
        return myIcon;
      }
    };
  }

  public boolean isValid() {
    if (myUsageInfo.getElement() == null) return false;
    for (int i = 0; i < myRangeMarkers.size(); i++) {
      RangeMarker rangeMarker = myRangeMarkers.get(i);
      if (!rangeMarker.isValid()) return false;
    }
    return true;
  }

  public boolean isReadOnly() {
    return isValid() && !myUsageInfo.getElement().isWritable();
  }

  public FileEditorLocation getLocation() {
    VirtualFile virtualFile = myPsiFile.getVirtualFile();
    if (virtualFile == null) return null;
    FileEditor editor = FileEditorManager.getInstance(getProject()).getSelectedEditor(virtualFile);
    if (editor == null) return null;

    return new TextEditorLocation(myUsageInfo.startOffset + myUsageInfo.getElement().getTextRange().getStartOffset(), (TextEditor)editor);
  }

  public void selectInEditor() {
    if (!isValid()) return;
    Editor editor = openTextEditor(false);
    RangeMarker marker = myRangeMarkers.get(0);
    editor.getSelectionModel().setSelection(marker.getStartOffset(), marker.getEndOffset());
  }

  public void highlightInEditor() {
    if (!isValid()) return;

    RangeMarker marker = myRangeMarkers.get(0);
    SelectInEditorManager.getInstance(getProject())
      .selectInEditor(getFile(), marker.getStartOffset(), marker.getEndOffset(), false, false);
  }

  public void navigate(boolean focus) {
    if (canNavigate()) {
      openTextEditor(focus);
    }
  }

  private Editor openTextEditor(boolean focus) {
    Project project = myPsiFile.getProject();
    return FileEditorManager.getInstance(project).openTextEditor(getDescriptor(), focus);
  }

  public boolean canNavigate() {
    final OpenFileDescriptor descriptor = getDescriptor();
    return descriptor != null ? descriptor.canNavigate() : false;
  }


  private OpenFileDescriptor getDescriptor() {
    return isValid() ? new OpenFileDescriptor(getProject(), getFile(), myRangeMarkers.get(0).getStartOffset()) : null;
  }

  private Project getProject() {
    Project project = myPsiFile.getProject();
    return project;
  }

  public static int compareTo(Usage usage) {
    return 0;
  }

  public String toString() {
    TextChunk[] textChunks = getPresentation().getText();
    StringBuffer result = new StringBuffer();
    for (int j = 0; j < textChunks.length; j++) {
      if (j > 0) result.append("|");
      TextChunk textChunk = textChunks[j];
      result.append(textChunk);
    }

    return result.toString();
  }

  public Module getModule() {
    if (!isValid()) return null;
    PsiElement element = myUsageInfo.getElement();
    VirtualFile virtualFile = getFile();
    if (virtualFile == null) return null;

    ProjectRootManager projectRootManager = ProjectRootManager.getInstance(element.getProject());
    ProjectFileIndex fileIndex = projectRootManager.getFileIndex();
    return fileIndex.getModuleForFile(virtualFile);
  }

  public OrderEntry getLibraryEntry() {
    if (!isValid()) return null;
    PsiElement element = myUsageInfo.getElement();
    PsiFile psiFile = element.getContainingFile();
    VirtualFile virtualFile = getFile();
    if (virtualFile == null) return null;

    ProjectRootManager projectRootManager = ProjectRootManager.getInstance(element.getProject());
    ProjectFileIndex fileIndex = projectRootManager.getFileIndex();

    if (psiFile instanceof PsiCompiledElement || fileIndex.isInLibrarySource(virtualFile)) {
      OrderEntry[] orders = fileIndex.getOrderEntriesForFile(psiFile.getVirtualFile());
      for (int i = 0; i < orders.length; i++) {
        OrderEntry order = orders[i];
        if (order instanceof LibraryOrderEntry || order instanceof JdkOrderEntry) {
          return order;
        }
      }
    }

    return null;
  }

  public VirtualFile getFile() {
    return isValid() ? myUsageInfo.getElement().getContainingFile().getVirtualFile() : null;
  }

  public int getLine() {
    return myLineNumber;
  }

  public boolean merge(MergeableUsage other) {
    if (!(other instanceof UsageInfo2UsageAdapter)) return false;
    UsageInfo2UsageAdapter u2 = (UsageInfo2UsageAdapter)other;
    if (myLineNumber != u2.myLineNumber || getFile() != u2.getFile()) return false;
    myRangeMarkers.addAll(u2.myRangeMarkers);
    initChunks();
    return true;
  }

  public void reset() {
    if (myRangeMarkers.size() > 1) {
      RangeMarker marker = myRangeMarkers.get(0);
      myRangeMarkers = new ArrayList<RangeMarker>();
      myRangeMarkers.add(marker);
      initChunks();
    }
  }

  public PsiElement getElement() {
    return myUsageInfo.getElement();
  }

  public boolean isNonCodeUsage() {
    return myUsageInfo.isNonCodeUsage;
  }

  public UsageInfo getUsageInfo() {
    return myUsageInfo;
  }
}
