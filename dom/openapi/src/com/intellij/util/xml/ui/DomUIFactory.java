/*
 * Copyright (c) 2005 Your Corporation. All Rights Reserved.
 */
package com.intellij.util.xml.ui;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.UserActivityWatcher;
import com.intellij.util.Function;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomUtil;
import com.intellij.util.xml.GenericDomValue;
import com.intellij.util.xml.Required;
import com.intellij.util.xml.reflect.DomCollectionChildDescription;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.TableCellEditor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * @author peter
 */
public abstract class DomUIFactory {
  public static Method GET_VALUE_METHOD = null;
  public static Method SET_VALUE_METHOD = null;
  public static Method GET_STRING_METHOD = null;
  public static Method SET_STRING_METHOD = null;

  static {
    try {
      GET_VALUE_METHOD = GenericDomValue.class.getMethod("getValue");
      GET_STRING_METHOD = GenericDomValue.class.getMethod("getStringValue");
      SET_VALUE_METHOD = findMethod(GenericDomValue.class, "setValue");
      SET_STRING_METHOD = findMethod(GenericDomValue.class, "setStringValue");
    }
    catch (NoSuchMethodException e) {
      Logger.getInstance("#com.intellij.util.xml.ui.DomUIFactory").error(e);
    }
  }

  @NotNull
  public static DomUIControl<GenericDomValue> createControl(GenericDomValue element) {
    return createControl(element, false);
  }

  @NotNull
  public static DomUIControl<GenericDomValue> createControl(GenericDomValue element, boolean commitOnEveryChange) {
    return createGenericValueControl(DomUtil.getGenericValueParameter(element.getDomElementType()), element, commitOnEveryChange);
  }

  public static DomUIControl createSmallDescriptionControl(DomElement parent, final boolean commitOnEveryChange) {
    return createLargeDescriptionControl(parent, commitOnEveryChange);
  }

  public static DomUIControl createLargeDescriptionControl(DomElement parent, final boolean commitOnEveryChange) {
    return getDomUIFactory().createTextControl(new DomCollectionWrapper<String>(parent, parent.getGenericInfo().getCollectionChildDescription("description")), commitOnEveryChange);
  }

  @NotNull
  private static BaseControl createGenericValueControl(final Type type, final GenericDomValue<?> element, boolean commitOnEveryChange) {
    final DomStringWrapper stringWrapper = new DomStringWrapper(element);
    final Class rawType = ReflectionUtil.getRawType(type);
    if (PsiClass.class.isAssignableFrom(rawType)) {
      return getDomUIFactory().createPsiClassControl(stringWrapper, commitOnEveryChange);
    }
    if (type.equals(PsiType.class)) {
      return getDomUIFactory().createPsiTypeControl(stringWrapper, commitOnEveryChange);
    }
    if (type instanceof Class && Enum.class.isAssignableFrom(rawType)) {
      return new ComboControl(stringWrapper, rawType);
    }
    if (DomElement.class.isAssignableFrom(rawType)) {
      final ComboControl control = new ComboControl(element);
      final Required annotation = element.getAnnotation(Required.class);
      if (annotation == null || !annotation.value() || !annotation.nonEmpty()) {
        control.setNullable(true);
      }
      return control;
    }

    final DomFixedWrapper wrapper = new DomFixedWrapper(element);
    if (type.equals(boolean.class) || type.equals(Boolean.class)) {
      return new BooleanControl(wrapper);
    }
    if (type.equals(String.class)) {
      return getDomUIFactory().createTextControl(wrapper, commitOnEveryChange);
    }

    final BaseControl customControl = getDomUIFactory().createCustomControl(type, stringWrapper, commitOnEveryChange);
    if (customControl != null) return customControl;

    return getDomUIFactory().createTextControl(stringWrapper, commitOnEveryChange);
  }

  public static Method findMethod(Class clazz, @NonNls String methodName) {
    final Method[] methods = clazz.getMethods();
    for (Method method : methods) {
      if (methodName.equals(method.getName())) {
        return method;
      }
    }
    return null;
  }

  public static TableCellEditor createCellEditor(GenericDomValue genericDomValue) {
    return getDomUIFactory().createCellEditor(genericDomValue, DomUtil.extractParameterClassFromGenericType(genericDomValue.getDomElementType()));
  }

  public abstract TableCellEditor createPsiClasssTableCellEditor(Project project, GlobalSearchScope searchScope);

  protected abstract TableCellEditor createCellEditor(DomElement element, Class type);

  public abstract UserActivityWatcher createEditorAwareUserActivityWatcher();

  public abstract void setupErrorOutdatingUserActivityWatcher(CommittablePanel panel, DomElement... elements);

  public abstract BaseControl createPsiClassControl(DomWrapper<String> wrapper, final boolean commitOnEveryChange);

  public abstract BaseControl createPsiTypeControl(DomWrapper<String> wrapper, final boolean commitOnEveryChange);

  public abstract BaseControl createTextControl(DomWrapper<String> wrapper, final boolean commitOnEveryChange);

  public abstract void registerCustomControl(Class aClass, Function<DomWrapper<String>, BaseControl> creator);

  @Nullable
  public abstract BaseControl createCustomControl(final Type type, DomWrapper<String> wrapper, final boolean commitOnEveryChange);

  public static BaseControl createTextControl(GenericDomValue value, final boolean commitOnEveryChange) {
    return getDomUIFactory().createTextControl(new DomStringWrapper(value), commitOnEveryChange);
  }

  public static BaseControl createTextControl(DomWrapper<String> wrapper) {
    return getDomUIFactory().createTextControl(wrapper, false);
  }

  public static DomUIFactory getDomUIFactory() {
    return ServiceManager.getService(DomUIFactory.class);
  }

  public DomUIControl createCollectionControl(DomElement element, DomCollectionChildDescription description) {
    final ColumnInfo columnInfo = createColumnInfo(description, element);
    final Class aClass = DomUtil.extractParameterClassFromGenericType(description.getType());
    return new DomCollectionControl<GenericDomValue<?>>(element, description, aClass == null, columnInfo);
  }

  public ColumnInfo createColumnInfo(final DomCollectionChildDescription description,
                                     final DomElement element) {
    final String presentableName = description.getCommonPresentableName(element);
    final Class aClass = DomUtil.extractParameterClassFromGenericType(description.getType());
    if (aClass != null) {
      if (Boolean.class.equals(aClass) || boolean.class.equals(aClass)) {
        return new BooleanColumnInfo(presentableName);
      }

      return new GenericValueColumnInfo(presentableName, aClass, createCellEditor(element, aClass));
    }

    return new StringColumnInfo(presentableName);
  }

  /**
   * Adds an error-checking square that is usually found in the top-right ange of a text editor
   * to the specified CaptionComponent.
   * @param captionComponent The component to add error panel to
   * @param elements DOM elements that will be error-checked
   * @return captionComponent
   */
  public abstract CaptionComponent addErrorPanel(CaptionComponent captionComponent, DomElement... elements);

  public abstract BackgroundEditorHighlighter createDomHighlighter(Project project, PerspectiveFileEditor editor, DomElement element);

}
