package kr.jobnations;

public enum Job {
    FARMER("농부", "작물 수확 시 추가 드롭", "다른 직업은 작물 수확 시 드롭 소실 가능"),
    MINER("광부", "광물 추가 드롭, 지하 야간 투시", "다른 직업은 광물 채굴 시 드롭 소실 가능"),
    FISHER("어부", "낚시 전리품 추가, 물속 속도 증가", "다른 직업은 낚시 성공 시 전리품 소실 가능"),
    HUNTER("사냥꾼", "몹 드롭 추가, 공격력 증가", "다른 직업은 몹 공격력 감소"),
    BUILDER("건축가", "블록 설치 시 돈 수익(다이아 시세 기준)", "다른 직업은 블록 설치 시 허기 추가 소모");

    private final String display;
    private final String merit;
    private final String demerit;

    Job(String display, String merit, String demerit) {
        this.display = display;
        this.merit = merit;
        this.demerit = demerit;
    }

    public String display() { return display; }
    public String merit() { return merit; }
    public String demerit() { return demerit; }

    public static Job fromInput(String input) {
        if (input == null) return null;
        for (Job j : values()) {
            if (j.display.equalsIgnoreCase(input) || j.name().equalsIgnoreCase(input)) return j;
        }
        return null;
    }
}
