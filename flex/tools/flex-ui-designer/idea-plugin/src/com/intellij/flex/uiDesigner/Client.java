package com.intellij.flex.uiDesigner;

import com.intellij.flex.uiDesigner.abc.AssetClassPoolGenerator;
import com.intellij.flex.uiDesigner.io.*;
import com.intellij.flex.uiDesigner.libraries.*;
import com.intellij.flex.uiDesigner.mxml.MxmlWriter;
import com.intellij.lang.javascript.flex.XmlBackedJSClassImpl;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.ArrayUtil;
import gnu.trove.TObjectObjectProcedure;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class Client implements Closable {
  protected final BlockDataOutputStream blockOut = new BlockDataOutputStream();
  protected final AmfOutputStream out = new AmfOutputStream(blockOut);

  private final MxmlWriter mxmlWriter = new MxmlWriter(out);

  private final InfoList<Module, ModuleInfo> registeredModules = new InfoList<Module, ModuleInfo>(true);
  private final InfoList<Project, ProjectInfo> registeredProjects = new InfoList<Project, ProjectInfo>();

  public static Client getInstance() {
    return ServiceManager.getService(Client.class);
  }

  public AmfOutputStream getOut() {
    return out;
  }

  public void setOut(OutputStream out) {
    blockOut.setOut(out);
  }

  public boolean isModuleRegistered(Module module) {
    return registeredModules.contains(module);
  }

  public InfoList<Project, ProjectInfo> getRegisteredProjects() {
    return registeredProjects;
  }

  @NotNull
  public Module getModule(int id) {
    return registeredModules.getElement(id);
  }

  @NotNull
  public Project getProject(int id) {
    return registeredProjects.getElement(id);
  }

  public void flush() throws IOException {
    out.flush();
  }

  @Override
  // synchronized due to out, otherwise may be NPE at out.closeWithoutFlush() (meaningful primary for tests)
  public synchronized void close() throws IOException {
    out.reset();

    registeredModules.clear();
    registeredProjects.clear();

    mxmlWriter.reset();

    LibraryManager.getInstance().reset();

    out.closeWithoutFlush();
  }

  private void beginMessage(ClientMethod method) {
    blockOut.assertStart();
    out.write(ClientMethod.METHOD_CLASS);
    out.write(method);
  }

  public void openProject(Project project) throws IOException {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.openProject);
      writeId(project);
      out.writeAmfUtf(project.getName());
      ProjectWindowBounds.write(project, out);
      hasError = false;
    }
    finally {
      if (hasError) {
        blockOut.rollback();
      }
      else {
        out.flush();
      }
    }
  }

  public void closeProject(final Project project) throws IOException {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.closeProject);
      writeId(project);
      hasError = false;
    }
    finally {
      try {
        if (hasError) {
          blockOut.rollback();
        }
        else {
          out.flush();
        }
      }
      finally {
        unregisterProject(project);
      }
    }
  }

  public void unregisterProject(final Project project) {
    DocumentFactoryManager.getInstance(project).reset();
    
    registeredProjects.remove(project);
    if (registeredProjects.isEmpty()) {
      registeredModules.clear();
    }
    else {
      registeredModules.remove(new TObjectObjectProcedure<Module, ModuleInfo>() {
        @Override
        public boolean execute(Module module, ModuleInfo info) {
          return module.getProject() != project;
        }
      });
    }
  }

  public void unregisterModule(final Module module) {
    registeredModules.remove(module);
    // todo close related documents
  }

  public void updateStringRegistry(StringRegistry.StringWriter stringWriter) throws IOException {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.updateStringRegistry);
      stringWriter.writeTo(out);
      hasError = false;
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  public void registerLibrarySet(LibrarySet librarySet) throws IOException {
    beginMessage(ClientMethod.registerLibrarySet);
    out.writeAmfUtf(librarySet.getId());
    
    LibrarySet parent = librarySet.getParent();
    if (parent == null) {
      out.write(0);
    }
    else {
      out.writeAmfUtf(parent.getId());
    }

    out.write(librarySet.getApplicationDomainCreationPolicy());
    final List<LibrarySetItem> items = librarySet.getItems();
    out.write(items.size());
    final LibraryManager libraryManager = LibraryManager.getInstance();
    for (LibrarySetItem item : items) {
      final Library library = item.library;
      final boolean registered = libraryManager.isRegistered(library);
      int flags = item.filtered ? 1 : 0;
      if (registered) {
        flags |= 2;
      }
      out.write(flags);

      if (registered) {
        out.writeShort(library.getId());
      }
      else {
        out.writeShort(libraryManager.add(library));

        out.writeAmfUtf(library.getPath());
        writeVirtualFile(library.getFile(), out);

        if (library.inheritingStyles == null) {
          out.writeShort(0);
        }
        else {
          out.write(library.inheritingStyles);
        }

        if (library.defaultsStyle == null) {
          out.write(0);
        }
        else {
          out.write(1);
          out.write(library.defaultsStyle);
        }
      }

      writeParents(items, item);
    }

    out.write(librarySet.getEmbedItems().size());
    for (LibrarySetEmbedItem item : librarySet.getEmbedItems()) {
      out.write(items.indexOf(item.parent));
      out.writeAmfUtf(item.path);
    }

    blockOut.end();
  }

  private void writeParents(List<LibrarySetItem> items, LibrarySetItem item) {
    out.write(item.parents.size());
    if (!item.parents.isEmpty()) {
      for (LibrarySetItem parent : item.parents) {
        out.write(items.indexOf(parent));
      }
    }
  }

  public void registerModule(Project project, ModuleInfo moduleInfo, String[] librarySetIds, StringRegistry.StringWriter stringWriter,
                             RequiredAssetsInfo requiredAssetsInfo) throws IOException {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.registerModule);

      if (requiredAssetsInfo == null) {
        out.writeShort(0);
        out.writeShort(0);
      }
      else {
        out.writeShort(requiredAssetsInfo.imageCount);
        out.writeShort(requiredAssetsInfo.swfCount);
        registeredProjects.getInfo(project).totalDefinedAssetContainerClassInfo = requiredAssetsInfo;
      }

      stringWriter.writeToIfStarted(out);

      out.writeShort(registeredModules.add(moduleInfo));
      writeId(project);
      out.write(librarySetIds);
      out.write(moduleInfo.getLocalStyleHolders(), "lsh", true);
      hasError = false;
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  public void openDocument(Module module, XmlFile psiFile) throws IOException {
    openDocument(module, psiFile, false, new ProblemsHolder(), new RequiredAssetsInfo());
  }

  /**
   * final, full open document — responsible for handle problemsHolder and requiredAssetsInfo — you must not do it
   */
  public boolean openDocument(Module module, XmlFile psiFile, boolean notifyOpened, ProblemsHolder problemsHolder,
                           RequiredAssetsInfo requiredAssetsInfo) throws IOException {
    final DocumentFactoryManager documentFactoryManager = DocumentFactoryManager.getInstance(module.getProject());
    final VirtualFile virtualFile = psiFile.getVirtualFile();
    final FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
    assert virtualFile != null;
    if (documentFactoryManager.isRegistered(virtualFile) && ArrayUtil.indexOf(fileDocumentManager.getUnsavedDocuments(),
      fileDocumentManager.getDocument(virtualFile)) != -1) {
      updateDocumentFactory(documentFactoryManager.getId(virtualFile), module, psiFile);
      return true;
    }

    final int factoryId = registerDocumentFactoryIfNeed(module, psiFile, virtualFile, false, problemsHolder, requiredAssetsInfo);
    if (factoryId == -1) {
      return false;
    }

    if (requiredAssetsInfo.imageCount > 0) {
      fillAssetClassPool(module, problemsHolder, requiredAssetsInfo.imageCount, ClientMethod.fillImageClassPool);
    }
    if (requiredAssetsInfo.swfCount > 0) {
      fillAssetClassPool(module, problemsHolder, requiredAssetsInfo.swfCount, ClientMethod.fillSwfClassPool);
    }

    if (!problemsHolder.isEmpty()) {
      DocumentProblemManager.getInstance().report(module.getProject(), problemsHolder);
    }

    beginMessage(ClientMethod.openDocument);
    writeId(module);
    out.writeShort(factoryId);
    out.write(notifyOpened);

    return true;
  }

  private void fillAssetClassPool(Module module, ProblemsHolder problemsHolder, int classCount, ClientMethod method)
      throws IOException {
    boolean hasError = true;
    try {
      beginMessage(method);
      writeId(module);
      out.writeShort(classCount);
      final RequiredAssetsInfo totalDefinedAssetContainerClassInfo = registeredProjects
          .getInfo(module.getProject()).totalDefinedAssetContainerClassInfo;
      AssetClassPoolGenerator.generate(method, classCount, totalDefinedAssetContainerClassInfo, blockOut);
      hasError = false;
    }
    catch (Throwable e) {
      problemsHolder.add(e);
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  private void finalizeMessage(boolean hasError) throws IOException {
    if (hasError) {
      blockOut.rollback();
    }
    else {
      blockOut.end();
    }

    out.resetAfterMessage();
  }
  
  public void updateDocumentFactory(int factoryId, Module module, XmlFile psiFile) throws IOException {
    boolean hasError = true;
    final ProblemsHolder problemsHolder = new ProblemsHolder();
    try {
      beginMessage(ClientMethod.updateDocumentFactory);
      writeId(module);
      out.writeShort(factoryId);

      RequiredAssetsInfo requiredAssetsInfo = new RequiredAssetsInfo();
      writeDocumentFactory(module, psiFile, problemsHolder, requiredAssetsInfo);

      beginMessage(ClientMethod.updateDocuments);
      writeId(module);
      out.writeShort(factoryId);
      hasError = false;
    }
    catch (Throwable e) {
      problemsHolder.add(e, psiFile.getVirtualFile());
    }
    finally {
      if (hasError) {
        blockOut.rollback();
      }
    }

    if (!problemsHolder.isEmpty()) {
      DocumentProblemManager.getInstance().report(module.getProject(), problemsHolder);
    }
  }

  private int registerDocumentFactoryIfNeed(Module module, XmlFile psiFile, VirtualFile virtualFile, boolean force,
                                            ProblemsHolder problemsHolder, RequiredAssetsInfo requiredAssetsInfo) throws IOException {
    final DocumentFactoryManager documentFactoryManager = DocumentFactoryManager.getInstance(module.getProject());
    final boolean registered = !force && documentFactoryManager.isRegistered(virtualFile);
    final int id = documentFactoryManager.getId(virtualFile);
    if (!registered) {
      boolean hasError = true;
      try {
        beginMessage(ClientMethod.registerDocumentFactory);
        writeId(module);
        out.writeShort(id);
        writeVirtualFile(virtualFile, out);

        JSClass jsClass = XmlBackedJSClassImpl.getXmlBackedClass(psiFile);
        assert jsClass != null;
        out.writeAmfUtf(jsClass.getQualifiedName());

        hasError = !writeDocumentFactory(module, psiFile, problemsHolder, requiredAssetsInfo);
      }
      catch (Throwable e) {
        problemsHolder.add(e, virtualFile);
      }
      finally {
        if (hasError) {
          blockOut.rollback();
          //noinspection ReturnInsideFinallyBlock
          return -1;
        }
      }
    }

    return id;
  }

  private boolean writeDocumentFactory(Module module, XmlFile psiFile, ProblemsHolder problemsHolder, RequiredAssetsInfo requiredAssetsInfo)
    throws IOException {
    XmlFile[] unregisteredDocumentReferences = mxmlWriter.write(psiFile, problemsHolder, requiredAssetsInfo);
    return unregisteredDocumentReferences == null || registerDocumentReferences(unregisteredDocumentReferences, module, problemsHolder,
                                                                                requiredAssetsInfo);
  }

  public boolean registerDocumentReferences(XmlFile[] files, Module module, ProblemsHolder problemsHolder,
                                            RequiredAssetsInfo requiredAssetsInfo) throws IOException {
    for (XmlFile file : files) {
      VirtualFile virtualFile = file.getVirtualFile();
      assert virtualFile != null;
      Module documentModule = ModuleUtil.findModuleForFile(virtualFile, file.getProject());
      if (module != documentModule && !isModuleRegistered(module)) {
        try {
          LibraryManager.getInstance().initLibrarySets(module, problemsHolder);
        }
        catch (InitException e) {
          FlexUIDesignerApplicationManager.LOG.error(e.getCause());
          // todo unclear error message (module will not be specified in this error message (but must be))
          problemsHolder.add(e.getMessage());
        }
      }

      // force register, it is registered (id allocated) only on server side
      if (registerDocumentFactoryIfNeed(module, file, virtualFile, true, problemsHolder, requiredAssetsInfo) == -1) {
        return false;
      }
    }

    return true;
  }

  public void qualifyExternalInlineStyleSource() {
    beginMessage(ClientMethod.qualifyExternalInlineStyleSource);
  }

  public static void writeVirtualFile(VirtualFile file, PrimitiveAmfOutputStream out) {
    out.writeAmfUtf(file.getUrl());
    out.writeAmfUtf(file.getPresentableUrl());
  }

  public void initStringRegistry() throws IOException {
    StringRegistry stringRegistry = ServiceManager.getService(StringRegistry.class);
    beginMessage(ClientMethod.initStringRegistry);
    out.write(stringRegistry.toArray());

    blockOut.end();
  }

  public void writeId(Module module, PrimitiveAmfOutputStream out) {
    out.writeShort(registeredModules.getId(module));
  }

  private void writeId(Module module) {
    writeId(module, out);
  }

  private void writeId(Project project) {
    writeId(registeredProjects.getId(project));
  }

  private void writeId(int id) {
    out.writeShort(id);
  }

  public static enum ClientMethod {
    openProject, closeProject, registerLibrarySet, registerModule, registerDocumentFactory, updateDocumentFactory, openDocument, updateDocuments,
    qualifyExternalInlineStyleSource, initStringRegistry, updateStringRegistry, fillImageClassPool, fillSwfClassPool;
    
    public static final int METHOD_CLASS = 0;
  }
}