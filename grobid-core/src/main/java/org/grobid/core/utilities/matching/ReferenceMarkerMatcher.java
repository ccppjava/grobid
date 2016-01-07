package org.grobid.core.utilities.matching;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.util.Version;
import org.grobid.core.data.BibDataSet;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.LayoutTokensUtil;
import org.grobid.core.utilities.Pair;
import org.grobid.core.utilities.counters.CntManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by zholudev on 18/12/15.
 * Matching reference markers to extracted citations
 */
public class ReferenceMarkerMatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceMarkerMatcher.class);

    public static final Pattern YEAR_PATTERN = Pattern.compile("[12][0-9]{3,3}");
    public static final Pattern AUTHOR_NAME_PATTERN = Pattern.compile("[A-Z][A-Za-z]+");
    private static final Pattern NUMBERED_CITATION_PATTERN = Pattern.compile(" *[\\(\\[]? *(?:\\d+[-–]\\d+,|\\d+, *)*(?:\\d+[-–]\\d+|\\d+)[\\)\\]]? *");
    public static final Pattern AUTHOR_SEPARATOR_PATTERN = Pattern.compile(";");
    public static final ClassicAnalyzer ANALYZER = new ClassicAnalyzer(Version.LUCENE_45);
    public static final int MAX_RANGE = 20;
    public static final Pattern NUMBERED_CITATIONS_SPLIT_PATTERN = Pattern.compile("[,;]");
    public static final Pattern AND_WORD_PATTERN = Pattern.compile("and");

    public enum Counters {
        MATCHED_REF_MARKERS,
        UNMATCHED_REF_MARKERS,
        NO_CANDIDATES,
        MANY_CANDIDATES,
        STYLE_AUTHORS,
        STYLE_NUMBERED,
        MATCHED_REF_MARKERS_AFTER_POST_FILTERING,
        MANY_CANDIDATES_AFTER_POST_FILTERING,
        NO_CANDIDATES_AFTER_POST_FILTERING,
        STYLE_OTHER
    }

    public class MatchResult {
        private String text;
        private List<LayoutToken> tokens;
        private BibDataSet bibDataSet;

        public MatchResult(String text, List<LayoutToken> tokens, BibDataSet bibDataSet) {
            this.text = text;
            this.tokens = tokens;
            this.bibDataSet = bibDataSet;
        }

        public String getText() {
            return text;
        }

        public List<LayoutToken> getTokens() {
            return tokens;
        }

        public BibDataSet getBibDataSet() {
            return bibDataSet;
        }
    }


    public static final Function<String, Object> IDENTITY = new Function<String, Object>() {
        @Override
        public Object apply(String s) {
            return s;
        }
    };
    private final LuceneIndexMatcher<BibDataSet, String> authorMatcher;
    private final LuceneIndexMatcher<BibDataSet, String> labelMatcher;
    private CntManager cntManager;


    public ReferenceMarkerMatcher(List<BibDataSet> bds, CntManager cntManager)
            throws EntityMatcherException {
        this.cntManager = cntManager;

        authorMatcher = new LuceneIndexMatcher<>(
                new Function<BibDataSet, Object>() {
                    @Override
                    public Object apply(BibDataSet bibDataSet) {
                        String authorString = bibDataSet.getResBib().getAuthors() + " et al";
                        if (bibDataSet.getResBib().getPublicationDate() != null) {
                            authorString += " " + bibDataSet.getResBib().getPublicationDate();
                        }
//                        System.out.println("Indexing: " + authorString);
                        return authorString;
                    }
                },
                IDENTITY
        );

        authorMatcher.setMustMatchPercentage(1.0);
        authorMatcher.load(bds);

        labelMatcher = new LuceneIndexMatcher<>(
                new Function<BibDataSet, Object>() {
                    @Override
                    public Object apply(BibDataSet bibDataSet) {
                        return bibDataSet.getRefSymbol();
                    }
                },
                IDENTITY
        );

        labelMatcher.setMustMatchPercentage(1.0);
        labelMatcher.load(bds);
    }

    public List<MatchResult> match(List<LayoutToken> refTokens) throws EntityMatcherException {
        String text = LayoutTokensUtil.toTextDehyphenized(LayoutTokensUtil.enrichWithNewLineInfo(refTokens));

        if (isAuthorCitationStyle(text)) {
            cntManager.i(Counters.STYLE_AUTHORS);
            return matchAuthorCitation(text, refTokens);
        } else if (isNumberedCitationReference(text)) {
            cntManager.i(Counters.STYLE_NUMBERED);
            return matchNumberedCitation(text, refTokens);
        } else {
            cntManager.i(Counters.STYLE_OTHER);
            System.out.println("Other style: " + text);
            return Collections.singletonList(new MatchResult(text, refTokens, null));
        }
    }


    private boolean isAuthorCitationStyle(String text) {
        return YEAR_PATTERN.matcher(text).find() && AUTHOR_NAME_PATTERN.matcher(text).find();
    }

    private static boolean isNumberedCitationReference(String t) {
        return NUMBERED_CITATION_PATTERN.matcher(t).matches();
    }

    private List<MatchResult> matchNumberedCitation(String input, List<LayoutToken> refTokens) throws EntityMatcherException {
        List<Pair<String, List<LayoutToken>>> labels = getNumberedLabels(refTokens);
        List<MatchResult> results = new ArrayList<>();
        for (Pair<String, List<LayoutToken>> label : labels) {
            String text = label.a;
            List<LayoutToken> labelToks = label.b;
            List<BibDataSet> matches = labelMatcher.match(text);
            if (matches.size() == 1) {
                cntManager.i(Counters.MATCHED_REF_MARKERS);
//                System.out.println("MATCHED: " + text + "\n" + matches.get(0).getRefSymbol() + "\n" + matches.get(0).getRawBib());

//                System.out.println("-----------");
                results.add(new MatchResult(text, labelToks, matches.get(0)));
            } else {
                cntManager.i(Counters.UNMATCHED_REF_MARKERS);
                if (matches.size() != 0) {
                    cntManager.i(Counters.MANY_CANDIDATES);
                    System.out.println("MANY CANDIDATES: " + input + "\n" + text + "\n");
                    for (BibDataSet bds : matches) {
                        System.out.println("  " + bds.getRawBib());
                    }

                    System.out.println("----------");
                } else {
                    cntManager.i(Counters.NO_CANDIDATES);
                    System.out.println("NO CANDIDATES: " + text + "\n" + text);
                    System.out.println("++++++++++++");
                }
                results.add(new MatchResult(text, labelToks, null));
            }
        }
        return results;
    }

    private static List<Pair<String, List<LayoutToken>>> getNumberedLabels(List<LayoutToken> layoutTokens) {
        List<List<LayoutToken>> split = LayoutTokensUtil.split(layoutTokens, NUMBERED_CITATIONS_SPLIT_PATTERN, true);
//                Splitter.on(NUMBERED_CITATIONS_SPLIT_PATTERN).omitEmptyStrings().splitToList(text);
        List<Pair<String, List<LayoutToken>>> res = new ArrayList<>();
        for (List<LayoutToken> s : split) {
            int minusPos = LayoutTokensUtil.tokenPos(s, "-");
            if (minusPos < 0) {
                res.add(new Pair<>(LayoutTokensUtil.toText(s), s));
            } else {
                try {
                    LayoutToken minusTok = s.get(minusPos);
                    List<LayoutToken> leftNumberToks = s.subList(0, minusPos);
                    List<LayoutToken> rightNumberToks = s.subList(minusPos + 1, s.size());


//                    List<String> toks = LuceneUtil.tokenizeString(ANALYZER, s);
                    Integer a;
                    Integer b;

                    a = Integer.valueOf(LuceneUtil.tokenizeString(ANALYZER, LayoutTokensUtil.toText(leftNumberToks)).get(0), 10);
                    b = Integer.valueOf(LuceneUtil.tokenizeString(ANALYZER, LayoutTokensUtil.toText(rightNumberToks)).get(0), 10);

//                    if (toks.size() == 1) {
//                        String[] sp = toks.get(0).split("-");
//                        a = Integer.valueOf(sp[0], 10);
//                        b = Integer.valueOf(sp[1], 10);
//                    } else if (toks.size() > 1) {
//                        a = Integer.valueOf(toks.get(0), 10);
//                        b = Integer.valueOf(toks.get(1), 10);
//                    } else {
//                        continue;
//                    }

                    if (a < b && b - a < MAX_RANGE) {
                        for (int i = a; i <= b; i++) {
                            List<LayoutToken> tokPtr;
                            if (i == a) {
                                tokPtr = leftNumberToks;
                            } else if (i == b) {
                                tokPtr = rightNumberToks;
                            } else {
                                tokPtr = Collections.singletonList(minusTok);
                            }

                            res.add(new Pair<>(String.valueOf(i), tokPtr));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Cannot parse citation reference range: " + s);
                }

            }
        }
        return res;
    }

    private List<MatchResult> matchAuthorCitation(String text, List<LayoutToken> refTokens) throws EntityMatcherException {
        List<List<LayoutToken>> split = splitAuthors(refTokens);
        List<MatchResult> results = new ArrayList<>();
        for (List<LayoutToken> splitItem : split) {
            String c = LayoutTokensUtil.toTextDehyphenized(splitItem);
            List<BibDataSet> matches = authorMatcher.match(c);
            if (matches.size() == 1) {
                cntManager.i(Counters.MATCHED_REF_MARKERS);
//                System.out.println("MATCHED: " + text + "\n" + c + "\n" + matches.get(0).getRawBib());

//                System.out.println("-----------");
                results.add(new MatchResult(c, splitItem, matches.get(0)));
            } else {
                if (matches.size() != 0) {
                    cntManager.i(Counters.MANY_CANDIDATES);
                    List<BibDataSet> filtered = postFilterMatches(c, matches);
                    if (filtered.size() == 1) {
                        results.add(new MatchResult(c, splitItem, filtered.get(0)));
                        cntManager.i(Counters.MATCHED_REF_MARKERS);
                        cntManager.i(Counters.MATCHED_REF_MARKERS_AFTER_POST_FILTERING);
                    } else {
                        cntManager.i(Counters.UNMATCHED_REF_MARKERS);
                        if (filtered.size() == 0) {
                            cntManager.i(Counters.NO_CANDIDATES_AFTER_POST_FILTERING);
                        } else {
                            cntManager.i(Counters.MANY_CANDIDATES_AFTER_POST_FILTERING);
                            System.out.println("MANY CANDIDATES: " + text + "\n" + c + "\n");
                            for (BibDataSet bds : matches) {
                                System.out.println("  " + bds.getRawBib());
                            }
                        }
                    }
//                    System.out.println("----------");
                } else {
                    cntManager.i(Counters.NO_CANDIDATES);
                    System.out.println("NO CANDIDATES: " + text + "\n" + c);
                    System.out.println("++++++++++++");
                }
                results.add(new MatchResult(c, splitItem, null));
            }
        }

        return results;
    }

    // splitting into individual citation references strings like in:
    // Kuwajima et al., 1985; Creighton, 1990; Ptitsyn et al., 1990;
    private static List<List<LayoutToken>> splitAuthors(List<LayoutToken> toks) {
        List<List<LayoutToken>> split = LayoutTokensUtil.split(toks, AUTHOR_SEPARATOR_PATTERN, true);
        List<List<LayoutToken>> result = new ArrayList<>();

        for (List<LayoutToken> splitTokens : split) {
            //cases like: Khechinashvili et al. (1973) and Privalov (1979)
            String text = LayoutTokensUtil.toText(splitTokens);
            int matchCount = matchCount(text, YEAR_PATTERN);
            if (matchCount == 2 && text.contains(" and ")) {
                for (List<LayoutToken> ys : LayoutTokensUtil.split(splitTokens, AND_WORD_PATTERN, true)) {
                    result.add(ys);
                }
            } else if (matchCount > 1) {
                result.addAll(LayoutTokensUtil.split(splitTokens, YEAR_PATTERN, true));
//                Matcher m = YEAR_PATTERN.matcher(splitTokens);
//                int prev = 0;
//                while (m.find()) {
//                    result.add(splitTokens.substring(prev, m.end()));
//                    prev = m.end();
//                }
            } else {
                result.add(splitTokens);
            }
        }
        return result;
    }

    private static int matchCount(String s, Pattern p) {
        Matcher m = p.matcher(s);
        int cnt = 0;
        while (m.find()) {
            cnt++;
        }
        return cnt;
    }

    private static int matchCount(List<LayoutToken> toks, Pattern p) {
        return matchCount(LayoutTokensUtil.toText(toks), p);
    }

    //if we match more than 1 citation based on name, then we leave only those citations that have author name first
    private List<BibDataSet> postFilterMatches(String c, List<BibDataSet> matches) {
        String[] sp = c.trim().split(" ");
        final String author = sp[0].toLowerCase();
        return Lists.newArrayList(Iterables.filter(matches, new Predicate<BibDataSet>() {
            @Override
            public boolean apply(BibDataSet bibDataSet) {
                return bibDataSet.getRawBib().trim().toLowerCase().startsWith(author);
            }
        }));
    }

    public static void main(String[] args) {
        Analyzer analyzer = ANALYZER;
//
        List<String> res = LuceneUtil.tokenizeString(analyzer, "Mark & van Gunsteren, 1992");
        for (String r : res) {
            System.out.println("Token: '" + r + "'");
        }
//        System.out.println(res);
        System.out.println(res.size());

//        List<String> res = splitAuthors("Van Kan et al., 1990, Van der Vos et al., 1992, Otte et al., 1992, Kwa et al., 1994b");
//        for (String s : res) {
//            System.out.println(s);
//        }
    }
}