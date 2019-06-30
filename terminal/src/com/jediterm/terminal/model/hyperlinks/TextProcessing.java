package com.jediterm.terminal.model.hyperlinks;

import com.google.common.collect.Lists;
import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.LinesBuffer;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author traff
 */
public class TextProcessing {
  private final List<HyperlinkFilter> myHyperlinkFilter;
  private TextStyle myHyperlinkColor;
  private HyperlinkStyle.HighlightMode myHighlightMode;
  private TerminalTextBuffer myTerminalTextBuffer;

  public TextProcessing(TextStyle hyperlinkColor, HyperlinkStyle.HighlightMode highlightMode) {
    myHyperlinkColor = hyperlinkColor;
    myHighlightMode = highlightMode;
    myHyperlinkFilter = Lists.newArrayList();
  }

  public void setTerminalTextBuffer(@NotNull TerminalTextBuffer terminalTextBuffer) {
    myTerminalTextBuffer = terminalTextBuffer;
  }

  public void processHyperlinks(@NotNull LinesBuffer buffer, int updatedLineInd) {
    if (myHyperlinkFilter.isEmpty()) return;
    myTerminalTextBuffer.lock();
    try {
      int startLineInd = updatedLineInd;
      while (startLineInd > 0 && buffer.getLine(startLineInd - 1).isWrapped()) {
        startLineInd--;
      }
      String lineStr = joinLines(buffer, startLineInd, updatedLineInd);
      for (HyperlinkFilter filter : myHyperlinkFilter) {
        LinkResult result = filter.apply(lineStr);
        if (result != null) {
          for (LinkResultItem item : result.getItems()) {
            TextStyle style = new HyperlinkStyle(myHyperlinkColor.getForeground(), myHyperlinkColor.getBackground(),
              item.getLinkInfo(), myHighlightMode, null);
            if (item.getStartOffset() < 0 || item.getEndOffset() > lineStr.length()) continue;

            int prevLinesLength = 0;
            for (int lineInd = startLineInd; lineInd <= updatedLineInd; lineInd++) {
              int startLineOffset = Math.max(prevLinesLength, item.getStartOffset());
              int endLineOffset = Math.min(prevLinesLength + myTerminalTextBuffer.getWidth(), item.getEndOffset());
              if (startLineOffset < endLineOffset) {
                buffer.getLine(lineInd).writeString(startLineOffset - prevLinesLength, new CharBuffer(lineStr.substring(startLineOffset, endLineOffset)), style);
              }
              prevLinesLength += myTerminalTextBuffer.getWidth();
            }
          }
        }
      }
    }
    finally {
      myTerminalTextBuffer.unlock();
    }
  }

  @NotNull
  private String joinLines(@NotNull LinesBuffer buffer, int startLineInd, int updatedLineInd) {
    StringBuilder result = new StringBuilder();
    for (int i = startLineInd; i <= updatedLineInd; i++) {
      String text = buffer.getLine(i).getText();
      if (i < updatedLineInd && text.length() < myTerminalTextBuffer.getWidth()) {
        text = text + new CharBuffer(CharUtils.NUL_CHAR, myTerminalTextBuffer.getWidth() - text.length());
      }
      result.append(text);
    }
    return result.toString();
  }

  public void addHyperlinkFilter(@NotNull HyperlinkFilter filter) {
    myHyperlinkFilter.add(filter);
  }
}
