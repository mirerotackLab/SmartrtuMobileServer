package kr.co.mirerotack.btsever1.ymodemOverTcp;

public class DetectionResult {
    public boolean isMatched;
    public String matchedLine;

    public DetectionResult(boolean isMatched, String matchedLine) {
        this.isMatched = isMatched;
        this.matchedLine = matchedLine;
    }
}
