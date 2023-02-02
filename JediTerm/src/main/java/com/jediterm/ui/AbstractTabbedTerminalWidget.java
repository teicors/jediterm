package com.jediterm.ui;

import com.jediterm.app.TtyConnectorWaitFor;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.*;
import com.jediterm.terminal.ui.settings.TabbedSettingsProvider;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * @author traff
 */
public abstract class AbstractTabbedTerminalWidget<T extends JediTermWidget> extends JPanel implements TerminalWidget, TerminalActionProvider {
  private final Object myLock = new Object();

  private TerminalPanelListener myTerminalPanelListener = null;

  private T myTermWidget = null;

  private AbstractTabs<T> myTabs;

  private final TabbedSettingsProvider mySettingsProvider;

  private final List<TabListener<T>> myTabListeners = new ArrayList<>();
  private TerminalActionProvider myNextActionProvider;

  private final Function<AbstractTabbedTerminalWidget<T>, T> myCreateNewSessionAction;

  private final JPanel myPanel;

  public AbstractTabbedTerminalWidget(@NotNull TabbedSettingsProvider settingsProvider, @NotNull Function<AbstractTabbedTerminalWidget<T>, T> createNewSessionAction) {
    super(new BorderLayout());
    mySettingsProvider = settingsProvider;
    myCreateNewSessionAction = createNewSessionAction;

    setFocusTraversalPolicy(new DefaultFocusTraversalPolicy());

    myPanel = new JPanel(new BorderLayout());
    myPanel.add(this, BorderLayout.CENTER);
  }

  @Override
  public T createTerminalSession(final TtyConnector ttyConnector) {
    final T terminal = createNewTabWidget();

    initSession(ttyConnector, terminal);

    return terminal;
  }

  public void initSession(TtyConnector ttyConnector, T terminal) {
    terminal.createTerminalSession(ttyConnector);
    if (myTabs != null) {
      int index = myTabs.indexOfComponent(terminal);
      if (index != -1) {
        myTabs.setTitleAt(index, generateUniqueName(terminal, myTabs));
      }
    }
    setupTtyConnectorWaitFor(ttyConnector, terminal);
  }

  public T createNewTabWidget() {
    final T terminal = createInnerTerminalWidget();

    terminal.setNextProvider(this);

    if (myTerminalPanelListener != null) {
      terminal.setTerminalPanelListener(myTerminalPanelListener);
    }

    if (myTermWidget == null && myTabs == null) {
      myTermWidget = terminal;
      Dimension size = terminal.getComponent().getSize();

      add(myTermWidget.getComponent(), BorderLayout.CENTER);
      setSize(size);

      if (myTerminalPanelListener != null) {
        myTerminalPanelListener.onPanelResize(RequestOrigin.User);
      }

      onSessionChanged();
    }
    else {
      if (myTabs == null) {
        myTabs = setupTabs();
      }

      addTab(terminal, myTabs);
    }
    return terminal;
  }

  public abstract T createInnerTerminalWidget();

  protected void setupTtyConnectorWaitFor(final TtyConnector ttyConnector, final T widget) {
    new TtyConnectorWaitFor(ttyConnector, Executors.newSingleThreadExecutor()).setTerminationCallback(integer -> {
      if (mySettingsProvider.shouldCloseTabOnLogout(ttyConnector)) {
        closeTab(widget);
      }
      return true;
    });
  }

  private void addTab(T terminal, AbstractTabs<T> tabs) {
    String name = generateUniqueName(terminal, tabs);

    addTab(terminal, tabs, name);
  }

  @NotNull String getTabName(@NotNull T terminal) {
    TtyConnector ttyConnector = terminal.getTtyConnector();
    return ttyConnector != null ? ttyConnector.getName() : "Session";
  }

  private String generateUniqueName(T terminal, AbstractTabs<T> tabs) {
    return generateUniqueName(mySettingsProvider.tabName(terminal.getTtyConnector(), getTabName(terminal)), tabs);
  }

  private void addTab(T terminal, AbstractTabs<T> tabs, String name) {
    tabs.addTab(name,
                terminal);

    tabs.setTabComponentAt(tabs.getTabCount() - 1, createTabComponent(tabs, terminal));
    tabs.setSelectedComponent(terminal);
  }

  private String generateUniqueName(String suggestedName, AbstractTabs<T> tabs) {
    final Set<String> names = new HashSet<>();
    for (int i = 0; i < tabs.getTabCount(); i++) {
      names.add(tabs.getTitleAt(i));
    }
    String newSdkName = suggestedName;
    int i = 0;
    while (names.contains(newSdkName)) {
      newSdkName = suggestedName + " (" + (++i) + ")";
    }
    return newSdkName;
  }

  private AbstractTabs<T> setupTabs() {
    final AbstractTabs<T> tabs = createTabbedPane();

    tabs.addChangeListener(new AbstractTabs.TabChangeListener() {
      @Override
      public void tabRemoved() {
        if (myTabs.getTabCount() == 1) {
          removeTabbedPane();
        }
      }

      @Override
      public void selectionChanged() {
        onSessionChanged();
      }
    });

    remove(myTermWidget);

    addTab(myTermWidget, tabs);

    myTermWidget = null;

    add(tabs.getComponent(), BorderLayout.CENTER);

    return tabs;
  }

  private void onSessionChanged() {
    T session = getCurrentSession();
    for (TabListener<T> tabListener : myTabListeners) {
      tabListener.onSelectedTabChanged(session);
    }
    session.getTerminalPanel().requestFocusInWindow();
  }

  protected abstract AbstractTabs<T> createTabbedPane();

  protected Component createTabComponent(AbstractTabs<T> tabs, T terminal) {
    return new TabComponent(tabs, terminal);
  }

  public void closeTab(final T terminal) {
    if (terminal != null) {
      if (myTabs != null && myTabs.indexOfComponent(terminal) != -1) {
        SwingUtilities.invokeLater(() -> removeTab(terminal));
        fireTabClosed(terminal);
      } else if (myTermWidget == terminal) {
        myTermWidget = null;
        fireTabClosed(terminal);
      }
    }
  }

  public void closeCurrentSession() {
    T session = getCurrentSession();
    session.close();
    closeTab(session);
  }

  public void dispose() {
    for (TerminalSession s : getAllTerminalSessions()) {
      if (s != null) s.close();
    }
  }

  private List<T> getAllTerminalSessions() {
    List<T> session = new ArrayList<>();
    if (myTabs != null) {
      for (int i = 0; i < myTabs.getTabCount(); i++) {
        session.add(getTerminalPanel(i));
      }
    }
    else {
      if (myTermWidget != null) {
        session.add(myTermWidget);
      }
    }
    return session;
  }

  public void removeTab(T terminal) {
    synchronized (myLock) {
      if (myTabs != null) {
        myTabs.remove(terminal);
      }
      onSessionChanged();
    }
  }

  private void removeTabbedPane() {
    myTermWidget = getTerminalPanel(0);
    myTabs.removeAll();
    remove(myTabs.getComponent());
    myTabs = null;
    add(myTermWidget.getComponent(), BorderLayout.CENTER);
  }

  @Override
  public List<TerminalAction> getActions() {
    return List.of(
      new TerminalAction(mySettingsProvider.getNewSessionActionPresentation(), input -> {
        handleNewSession();
        return true;
      }).withMnemonicKey(KeyEvent.VK_N),
      new TerminalAction(mySettingsProvider.getCloseSessionActionPresentation(), input -> {
        closeCurrentSession();
        return true;
      }).withMnemonicKey(KeyEvent.VK_S),
      new TerminalAction(mySettingsProvider.getNextTabActionPresentation(), input -> {
        selectNextTab();
        return true;
      }).withEnabledSupplier(() -> myTabs != null && myTabs.getSelectedIndex() < myTabs.getTabCount() - 1),
      new TerminalAction(mySettingsProvider.getPreviousTabActionPresentation(), input -> {
        selectPreviousTab();
        return true;
      }).withEnabledSupplier(() -> myTabs != null && myTabs.getSelectedIndex() > 0)
    );
  }

  private void selectPreviousTab() {
    myTabs.setSelectedIndex(myTabs.getSelectedIndex() - 1);
  }

  private void selectNextTab() {
    myTabs.setSelectedIndex(myTabs.getSelectedIndex() + 1);
  }

  @Override
  public TerminalActionProvider getNextProvider() {
    return myNextActionProvider;
  }

  @Override
  public void setNextProvider(TerminalActionProvider provider) {
    myNextActionProvider = provider;
  }

  private void handleNewSession() {
    myCreateNewSessionAction.apply(this);
  }

  public static class TabRenamer {

    public interface RenameCallBack {

      void setComponent(Component c);

      void setNewName(int index, String name);
    }

    public void install(final int selectedIndex, final String text, final Component label, final RenameCallBack callBack) {
      final JTextField textField = createTextField();

      textField.setOpaque(false);

      textField.setDocument(new JTextFieldLimit(50));
      textField.setText(text);

      final FocusAdapter focusAdapter = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent focusEvent) {
          finishRename(selectedIndex, label, textField.getText(), callBack);
        }
      };
      textField.addFocusListener(focusAdapter);
      textField.addKeyListener(new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent keyEvent) {
          if (keyEvent.getKeyCode() == KeyEvent.VK_ESCAPE) {
            textField.removeFocusListener(focusAdapter);
            finishRename(selectedIndex, label, null, callBack);
          }
          else if (keyEvent.getKeyCode() == KeyEvent.VK_ENTER) {
            textField.removeFocusListener(focusAdapter);
            finishRename(selectedIndex, label, textField.getText(), callBack);
          }
          else {
            super.keyPressed(keyEvent);
          }
        }
      });

      callBack.setComponent(textField);


      textField.requestFocus();
      textField.selectAll();
    }

    protected JTextField createTextField() {
      return new JTextField();
    }

    private static void finishRename(int index, Component label, String newName, RenameCallBack callBack) {
      if (newName != null) {
        callBack.setNewName(index, newName);
      }
      callBack.setComponent(label);
    }
  }

  private class TabComponent extends JPanel implements FocusListener {

    private final T myTerminal;

    private final MyLabelHolder myLabelHolder = new MyLabelHolder();

    private class MyLabelHolder extends JPanel {

      public void set(Component c) {
        myLabelHolder.removeAll();
        myLabelHolder.add(c);
        myLabelHolder.validate();
        myLabelHolder.repaint();
      }
    }

    class TabComponentLabel extends JLabel {

      public String getText() {
        if (myTabs != null) {
          int i = myTabs.indexOfTabComponent(TabComponent.this);
          if (i != -1) {
            return myTabs.getTitleAt(i);
          }
        }
        return null;
      }
    }

    private TabComponent(final @NotNull AbstractTabs<T> tabs, final T terminal) {
      super(new FlowLayout(FlowLayout.LEFT, 0, 0));
      myTerminal = terminal;
      setOpaque(false);

      setFocusable(false);

      addFocusListener(this);

      //make JLabel read titles from JTabbedPane
      JLabel label = new TabComponentLabel();

      label.addFocusListener(this);

      //add more space between the label and the button
//      label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));

      label.addMouseListener(new MouseAdapter() {

        @Override
        public void mouseReleased(MouseEvent event) {
          handleMouse(event);
        }

        @Override
        public void mousePressed(MouseEvent event) {
          tabs.setSelectedComponent(terminal);
          handleMouse(event);
        }
      });

      myLabelHolder.set(label);
      add(myLabelHolder);
    }

    protected void handleMouse(MouseEvent event) {
      if (event.isPopupTrigger()) {
        JPopupMenu menu = createPopup();
        menu.show(event.getComponent(), event.getX(), event.getY());
      }
      else {
        if (event.getClickCount() == 2 && !event.isConsumed()) {
          event.consume();
          renameTab();
        }
      }
    }

    protected JPopupMenu createPopup() {
      JPopupMenu popupMenu = new JPopupMenu();

      TerminalAction.addToMenu(popupMenu, AbstractTabbedTerminalWidget.this);

      JMenuItem rename = new JMenuItem("Rename Tab");

      rename.addActionListener(actionEvent -> renameTab());

      popupMenu.add(rename);

      return popupMenu;
    }

    private void renameTab() {
      final int selectedIndex = myTabs.getSelectedIndex();
      final JLabel label = (JLabel)myLabelHolder.getComponent(0);

      new TabRenamer().install(selectedIndex, label.getText(), label, new TabRenamer.RenameCallBack() {
        @Override
        public void setComponent(Component c) {
          myLabelHolder.set(c);
        }

        @Override
        public void setNewName(int index, String name) {
          if (myTabs != null) {
            myTabs.setTitleAt(index, name);
          }
        }
      });
    }

    @Override
    public void focusGained(FocusEvent e) {
      myTerminal.getComponent().requestFocusInWindow();
    }

    @Override
    public void focusLost(FocusEvent e) {

    }
  }

  public AbstractTabs<T> getTerminalTabs() {
    return myTabs;
  }

  @Override
  public JComponent getComponent() {
    return myPanel;
  }

  public JComponent getFocusableComponent() {
    return myTabs != null ? myTabs.getComponent() : myTermWidget != null ? myTermWidget : this;
  }

  @Override
  public JComponent getPreferredFocusableComponent() {
    return getFocusableComponent();
  }

  @Override
  public boolean canOpenSession() {
    return true;
  }

  @Override
  public void setTerminalPanelListener(TerminalPanelListener terminalPanelListener) {
    if (myTabs != null) {
      for (int i = 0; i < myTabs.getTabCount(); i++) {
        getTerminalPanel(i).setTerminalPanelListener(terminalPanelListener);
      }
    } else if (myTermWidget!= null) {
      myTermWidget.setTerminalPanelListener(terminalPanelListener);
    }
    myTerminalPanelListener = terminalPanelListener;
  }

  public @NotNull T getCurrentSession() {
    if (myTabs != null) {
      return getTerminalPanel(myTabs.getSelectedIndex());
    }
    return Objects.requireNonNull(myTermWidget);
  }

  @Override
  public TerminalDisplay getTerminalDisplay() {
    return getCurrentSession().getTerminalDisplay();
  }

  private @NotNull T getTerminalPanel(int index) {
    Objects.checkIndex(index, myTabs.getTabCount());
    return myTabs.getComponentAt(index);
  }

  public void addTabListener(TabListener<T> listener) {
    myTabListeners.add(listener);
  }

  private void fireTabClosed(T terminal) {
    for (TabListener<T> l : myTabListeners) {
      l.tabClosed(terminal);
    }
  }

  public interface TabListener<T extends JediTermWidget> {
    void tabClosed(T terminal);
    void onSelectedTabChanged(@NotNull T terminal);
  }

  @Override
  public void addListener(TerminalWidgetListener listener) {
  }

  @Override
  public void removeListener(TerminalWidgetListener listener) {
  }

  public TabbedSettingsProvider getSettingsProvider() {
    return mySettingsProvider;
  }
}
