package no.uib.pap.pathwaymatcher;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.Files;
import no.uib.pap.methods.analysis.ora.Analysis;
import no.uib.pap.methods.search.Search;
import no.uib.pap.model.*;
import no.uib.pap.model.Error;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.tuple.Pair;

import java.io.*;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static no.uib.pap.model.Error.ERROR_WITH_OUTPUT_FILE;
import static no.uib.pap.model.Error.sendError;
import static no.uib.pap.model.InputPatterns.matches_ChrBp;
import static no.uib.pap.model.InputPatterns.matches_Rsid;
import static no.uib.pap.model.Warning.EMPTY_ROW;
import static no.uib.pap.model.Warning.INVALID_ROW;
import static no.uib.pap.model.Warning.sendWarning;

public class PathwayMatcher {

    /**
     * The object to hold the command line arguments for PathwayMatcher.
     */
    static Options options = new Options();
    static CommandLine commandLine;

    static List<String> input;
    static BufferedWriter outputSearch;
    static BufferedWriter outputAnalysis;
    static BufferedWriter outputVertices;
    static BufferedWriter outputInternalEdges;
    static BufferedWriter outputExternalEdges;
    static String outputPath = "";
    static InputType inputType;
    static MatchType matchType = MatchType.SUPERSET;
    static Pair<List<String[]>, MessageStatus> searchResult;
    static MessageStatus analysisResult;

    /**
     * The column separator.
     */
    public static final String separator = "\t";
    /**
     * The system specific end of file.
     */
    public static final String eol = System.lineSeparator();
    static Long margin = 0L;

    /**
     * Static mapping data structures
     */
    static ImmutableMap<String, Reaction> iReactions;
    static ImmutableMap<String, Pathway> iPathways;
    static ImmutableSetMultimap<String, String> imapRsIdsToProteins;
    static ImmutableSetMultimap<Long, String> imapChrBpToProteins;
    static ImmutableSetMultimap<String, String> imapGenesToProteins;
    static ImmutableSetMultimap<String, String> imapEnsemblToProteins;
    static ImmutableSetMultimap<String, Proteoform> imapProteinsToProteoforms;
    static ImmutableSetMultimap<Proteoform, String> imapProteoformsToReactions;
    static ImmutableSetMultimap<String, String> imapProteinsToReactions;
    static ImmutableSetMultimap<String, String> imapReactionsToPathways;
    static ImmutableSetMultimap<String, String> imapPathwaysToTopLevelPathways;

    static HashSet<String> hitPathways;
    static HashSet<String> inputProteins = new HashSet<>(); // These may not be in the reference data
    static HashSet<Proteoform> inputProteoforms = new HashSet<>(); // These may not be in the reference data
    static HashSet<String> hitProteins = new HashSet<>(); // These are in the reference data
    static HashSet<Proteoform> hitProteoforms = new HashSet<>(); // These are in the reference data

    public static void main(String args[]) {

        // ******** ******** Read and process command line arguments ******** ********
        addOption("t", "inputType", true, "Input inputType: GENE|ENSEMBL|UNIPROT|PEPTIDE|RSID|PROTEOFORM", true);
        addOption("r", "range", true, "Ptm sites margin of error", false);
        addOption("tlp", "toplevelpathways", false, "Show Top Level Pathway columns", false);
        addOption("m", "matching", true, "Proteoform match criteria: EXACT|ONE|SUPERSET", false);
        addOption("i", "input", true, "Input file", true);
        addOption("o", "output", true, "Output path", false);
        addOption("g", "graph", false, "Create igraph file with connections of proteins", false);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        try {
            commandLine = parser.parse(options, args);
            if (commandLine.hasOption("r")) {
                margin = Long.valueOf(commandLine.getOptionValue("r"));
            }
            if (commandLine.hasOption("m")) {
                String matchTypeValue = commandLine.getOptionValue("m").toUpperCase();
                if (MatchType.isValueOf(matchTypeValue)) {
                    matchType = MatchType.valueOf(matchTypeValue);
                } else {
                    System.out.println(Error.INVALID_MATCHING_TYPE.getMessage());
                    System.exit(Error.INVALID_MATCHING_TYPE.getCode());
                }
            }
        } catch (org.apache.commons.cli.ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("java -jar PathwayMatcher.jar <options>", options);
            if (args.length == 0) {
                System.exit(Error.NO_ARGUMENTS.getCode());
            }
            if (e.getMessage().startsWith("Missing required option: i")) {
                System.exit(Error.NO_INPUT.getCode());
            }
            if (e.getMessage().startsWith("Missing required option:")) {
                System.exit(Error.MISSING_ARGUMENT.getCode());
            }
            System.exit(Error.COMMAND_LINE_ARGUMENTS_PARSING_ERROR.getCode());
        }

        // ******** ******** Read input ******** ********
        File file = new File(commandLine.getOptionValue("i"));
        try {
            input = Files.readLines(file, Charset.defaultCharset());
            for (String line : input) {
                line = line.trim();
            }
        } catch (IOException e) {
            System.out.println("The input file: " + commandLine.getOptionValue("i") + " was not found."); // TODO Error
            System.exit(Error.COULD_NOT_READ_INPUT_FILE.getCode());
        }

        // ******** ******** Create output files ******** ********
        if (commandLine.hasOption("o")) {
            outputPath = commandLine.getOptionValue("o");
            outputPath = outputPath.endsWith("/") ? outputPath : outputPath + "/";
        }
        try {
            file = new File(outputPath + "search.tsv");
            if (outputPath.length() > 0) {
                file.getParentFile().mkdirs();
            }
            outputSearch = new BufferedWriter(new FileWriter(file));
            outputAnalysis = new BufferedWriter(new FileWriter(outputPath + "analysis.tsv"));

            // ******** ******** Process the input ******** ********
            // Load static structures needed for all the cases
            iReactions = (ImmutableMap<String, Reaction>) getSerializedObject("iReactions.gz");
            iPathways = (ImmutableMap<String, Pathway>) getSerializedObject("iPathways.gz");
            imapProteinsToReactions = (ImmutableSetMultimap<String, String>) getSerializedObject(
                    "imapProteinsToReactions.gz");
            imapReactionsToPathways = (ImmutableSetMultimap<String, String>) getSerializedObject(
                    "imapReactionsToPathways.gz");
            imapPathwaysToTopLevelPathways = null;
            if (commandLine.hasOption("tlp")) {
                imapPathwaysToTopLevelPathways = (ImmutableSetMultimap<String, String>) getSerializedObject(
                        "imapPathwaysToTopLevelPathways.gz");
            }
            hitPathways = new HashSet<>();

            String inputTypeValue = commandLine.getOptionValue("inputType").toUpperCase();
            if (!InputType.isValueOf(inputTypeValue)) {
                System.out.println("Invalid input type: " + inputTypeValue);
                System.exit(Error.INVALID_INPUT_TYPE.getCode());
            }

            inputType = InputType.valueOf(inputTypeValue);
            switch (inputType) {
                case GENE:
                case GENES:
                    imapGenesToProteins = (ImmutableSetMultimap<String, String>) getSerializedObject("imapGenesToProteins.gz");
                    searchResult = Search.searchWithGene(input, iReactions, iPathways, imapGenesToProteins,
                            imapProteinsToReactions, imapReactionsToPathways, imapPathwaysToTopLevelPathways,
                            commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithGene(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case ENSEMBL:
                case ENSEMBLS:
                    imapEnsemblToProteins = (ImmutableSetMultimap<String, String>) getSerializedObject("imapEnsemblToProteins.gz");
                    searchResult = Search.searchWithEnsembl(input, iReactions, iPathways, imapEnsemblToProteins,
                            imapProteinsToReactions, imapReactionsToPathways, imapPathwaysToTopLevelPathways,
                            commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithEnsembl(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case UNIPROT:
                case UNIPROTS:
                    searchResult = Search.searchWithUniProt(input, iReactions, iPathways, imapProteinsToReactions,
                            imapReactionsToPathways, imapPathwaysToTopLevelPathways, commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithUniProt(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case PROTEOFORM:
                case PROTEOFORMS:
                    imapProteinsToProteoforms = (ImmutableSetMultimap<String, Proteoform>) getSerializedObject("imapProteinsToProteoforms.gz");
                    imapProteoformsToReactions = (ImmutableSetMultimap<Proteoform, String>) getSerializedObject("imapProteoformsToReactions.gz");
                    searchResult = Search.searchWithProteoform(input, matchType, margin, iReactions, iPathways,
                            imapProteinsToProteoforms, imapProteoformsToReactions, imapReactionsToPathways,
                            imapPathwaysToTopLevelPathways, commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithProteoform(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteoformsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case RSID:
                case RSIDS:
                    HashSet<String> rsIdSet = new HashSet<>();
                    // Get the unique set of Variants
                    int row = 0;
                    for (String rsid : input) {
                        row++;
                        if (rsid.isEmpty()) {
                            sendWarning(EMPTY_ROW, row);
                            continue;
                        }
                        if (!matches_Rsid(rsid)) {
                            sendWarning(INVALID_ROW, row);
                            continue;
                        }
                        rsIdSet.add(rsid);
                    }

                    outputSearchWithRsid();
                    for (int chr = 1; chr <= 22; chr++) {
                        System.out.println("Loading data for chromosome " + chr);
                        imapRsIdsToProteins = (ImmutableSetMultimap<String, String>) getSerializedObject("imapRsIdsToProteins" + chr + ".gz");
                        System.out.println("Matching...");
                        searchResult = Search.searchWithRsId(rsIdSet, iReactions, iPathways, imapRsIdsToProteins,
                                imapProteinsToReactions, imapReactionsToPathways, imapPathwaysToTopLevelPathways,
                                commandLine.hasOption("tlp"), hitProteins, hitPathways);
                        writeSearchResults(searchResult.getKey());
                    }

                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case CHRBP:
                case CHRBPS:
                    TreeMultimap<Integer, Long> chrBpMap = TreeMultimap.create();
                    row = 0;
                    for (String line : input) {
                        row++;
                        if (line.isEmpty()) {
                            sendWarning(EMPTY_ROW, row);
                            continue;
                        }
                        if (!matches_ChrBp(line)) {
                            sendWarning(INVALID_ROW, row);
                            continue;
                        }
                        Snp snp = getSnpFromChrBp(line);
                        chrBpMap.put(snp.getChr(), snp.getBp());
                    }
                    outputSearchWithChrBp();
                    for (int chr = 1; chr <= 22; chr++) {
                        System.out.println("Loading data for chromosome " + chr);
                        imapChrBpToProteins = (ImmutableSetMultimap<Long, String>) getSerializedObject("imapChrBpToProteins" + chr + ".gz");
                        searchResult = Search.searchWithChrBp(chr, chrBpMap.get(chr), iReactions, iPathways, imapChrBpToProteins,
                                imapProteinsToReactions, imapReactionsToPathways, imapPathwaysToTopLevelPathways,
                                commandLine.hasOption("tlp"), hitProteins, hitPathways);
                        writeSearchResults(searchResult.getKey());
                    }
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case VCF:
                    imapChrBpToProteins = (ImmutableSetMultimap<Long, String>) getSerializedObject("imapChrBpToProteins.gz");
                    searchResult = Search.searchWithVCF(input, iReactions, iPathways, imapChrBpToProteins,
                            imapProteinsToReactions, imapReactionsToPathways, imapPathwaysToTopLevelPathways,
                            commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithUniProt(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case PEPTIDE:
                case PEPTIDES:
                    searchResult = Search.searchWithPeptide(input, iReactions, iPathways, imapProteinsToReactions,
                            imapReactionsToPathways, imapPathwaysToTopLevelPathways, commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithUniProt(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(iPathways, imapProteinsToReactions.keySet().size(),
                            hitProteins, hitPathways);
                    break;
                case MODIFIEDPEPTIDE:
                case MODIFIEDPEPTIDES:
                    imapProteinsToProteoforms = (ImmutableSetMultimap<String, Proteoform>) getSerializedObject("imapProteinsToProteoforms.gz");
                    imapProteoformsToReactions = (ImmutableSetMultimap<Proteoform, String>) getSerializedObject("imapProteoformsToReactions.gz");
                    searchResult = Search.searchWithModifiedPeptide(input, matchType, margin, iReactions, iPathways,
                            imapProteinsToProteoforms, imapProteoformsToReactions, imapReactionsToPathways,
                            imapPathwaysToTopLevelPathways, commandLine.hasOption("tlp"), hitProteins, hitPathways);
                    outputSearchWithProteoform(searchResult.getKey());
                    System.out.println("Matching results writen to: " + outputPath + "search.csv");
                    System.out.println("Starting ORA analysis...");
                    analysisResult = Analysis.analysis(
                            iPathways,
                            imapProteoformsToReactions.keySet().size(),
                            hitProteins,
                            hitPathways);
                    break;
                default:
                    System.out.println("Input inputType not supported.");
                    System.exit(1);
                    break;
            }

            writeAnalysisResult(hitPathways, iPathways);
            System.out.println("Analysis results writen to: " + outputPath + "analysis.csv");

            if (commandLine.hasOption("g")) {
                writeConnectionGraph(hitPathways, iPathways);
            }

            outputSearch.close();
            outputAnalysis.close();

        } catch (IOException e1) {
            System.out.println(Error.COULD_NOT_WRITE_TO_OUTPUT_FILES.getMessage() + ": " + outputPath + "search.txt  " + eol + outputPath
                    + "analysis.txt");
            System.exit(Error.COULD_NOT_WRITE_TO_OUTPUT_FILES.getCode());
        }
    }

    private static void writeConnectionGraph(HashSet<String> hitPathways, ImmutableMap<String, Pathway> iPathways) throws IOException {
        System.out.println("Creating connection graph...");

        //Create output files
        outputVertices = new BufferedWriter(new FileWriter(outputPath + "vertices.tsv"));
        outputInternalEdges = new BufferedWriter(new FileWriter(outputPath + "internalEdges.tsv"));
        outputExternalEdges = new BufferedWriter(new FileWriter(outputPath + "externalEdges.tsv"));

        // Write headers
        outputVertices.write("id" + separator + " name" + eol);
        outputInternalEdges.write("from" + separator + "to" + separator + "type" + separator + "container_stId" + separator + "role_from" + separator + "role_to" + eol);
        outputExternalEdges.write("from" + separator + "to" + separator + "type" + separator + "container_stId" + separator + "role_from" + separator + "role_to" + eol);

        // Load static mapping
        ImmutableMap<String, String> iProteins = (ImmutableMap<String, String>) getSerializedObject("iProteins.gz");
        ImmutableSetMultimap<String, String> imapProteinsToComplexes = (ImmutableSetMultimap<String, String>) getSerializedObject("imapProteinsToComplexes.gz");
        ImmutableSetMultimap<String, String> imapComplexesToComponents = (ImmutableSetMultimap<String, String>) getSerializedObject("imapComplexesToComponents.gz");
        ImmutableSetMultimap<String, String> imapSetsToMembersAndCandidates = (ImmutableSetMultimap<String, String>) getSerializedObject("imapSetsToMembersAndCandidates.gz");
        HashSet<String> checkedComplexes = new HashSet<>();
        HashSet<String> checkedReactions = new HashSet<>();
        HashSet<String> checkedSets = new HashSet<>();

        // Write the vertices file
        for (String protein : hitProteins) {
            String line = String.join(separator, protein, iProteins.get(protein));
            outputVertices.write(line);
            outputVertices.newLine();
        }
        outputVertices.close();
        System.out.println("Finished writing " + outputPath + "vertices.tsv");

        // Write edges among input proteins

        for (String protein : hitProteins) {
            // Output reaction neighbours
            for (String reaction : imapProteinsToReactions.get(protein)) {

                // Avoid adding edges related to the same reaction
                if (checkedReactions.contains(reaction)) {
                    continue;
                }
                checkedReactions.add(reaction);

                for (Map.Entry<String, Role> from_participant : iReactions.get(reaction).getParticipants().entries()) {
                    for (Map.Entry<String, Role> to_participant : iReactions.get(reaction).getParticipants().entries()) {
                        if (from_participant.getKey().compareTo(to_participant.getKey()) < 0) {   // Only different and ordered pairs to avoid duplicate edges
                            if (hitProteins.contains(from_participant.getKey()) || hitProteins.contains(to_participant.getKey())) {
                                String line = String.join(
                                        separator,
                                        from_participant.getKey(),
                                        to_participant.getKey(),
                                        "Reaction",
                                        reaction,
                                        from_participant.getValue().toString(),
                                        to_participant.getValue().toString());
                                if (hitProteins.contains(from_participant.getKey()) && hitProteins.contains(to_participant.getKey())) {
                                    outputInternalEdges.write(line);
                                    outputInternalEdges.newLine();
                                } else {
                                    outputExternalEdges.write(line);
                                    outputExternalEdges.newLine();
                                }
                            }
                        }
                    }
                }
            }

            // Output complex neighbours
            for (String complex : imapProteinsToComplexes.get(protein)) {

                // Avoid adding edges related to the same complex
                if (checkedComplexes.contains(complex)) {
                    continue;
                }
                checkedComplexes.add(complex);

                // For each pair of components in this complex
                for (String from_component : imapComplexesToComponents.get(complex)) {
                    for (String to_component : imapComplexesToComponents.get(complex)) {
                        if (from_component.compareTo(to_component) < 0) {
                            if (hitProteins.contains(from_component) || hitProteins.contains(to_component)) {
                                String line = String.join(separator, from_component, to_component, "Complex", complex, "component", "component");
                                if (hitProteins.contains(from_component) && hitProteins.contains(to_component)) {
                                    outputInternalEdges.write(line);
                                    outputInternalEdges.newLine();
                                } else {
                                    outputExternalEdges.write(line);
                                    outputExternalEdges.newLine();
                                }
                            }
                        }
                    }
                }
            }

            // Output set neighbours
            for (String set : imapSetsToMembersAndCandidates.get(protein)) {

                // Avoid adding edges related to the same complex
                if (checkedSets.contains(set)) {
                    continue;
                }
                checkedSets.add(set);

                // For each pair of members of this set
                for (String from_member : imapSetsToMembersAndCandidates.get(set)) {
                    for (String to_member : imapSetsToMembersAndCandidates.get(set)) {
                        if (from_member.compareTo(to_member) < 0) {
                            if (hitProteins.contains(from_member) || hitProteins.contains(to_member)) {
                                String line = String.join(separator, protein, to_member, "Set", set, "member/candidate", "member/candidate");
                                if (hitProteins.contains(from_member) && hitProteins.contains(to_member)) {
                                    outputInternalEdges.write(line);
                                    outputInternalEdges.newLine();
                                } else {
                                    outputExternalEdges.write(line);
                                    outputExternalEdges.newLine();
                                }
                            }
                        }
                    }
                }
            }
        }

        outputInternalEdges.close();
        outputExternalEdges.close();
        System.out.println("Finished writing edges files: \n" + outputPath + "internalEdges.tsv\n" + outputPath + "externalEdges.tsv");
    }

    private static void writeAnalysisResult(HashSet<String> hitPathways, ImmutableMap<String, Pathway> iPathways) {
        try {

            // Write headers of the file
            outputAnalysis.write("Pathway StId" + separator + "Pathway Name" + separator + "# Entities Found"
                    + separator + "# Entities Total" + separator + "Entities Ratio" + separator + "Entities P-Value"
                    + separator + "Significant" + separator + "Entities FDR" + separator + "# Reactions Found"
                    + separator + "# Reactions Total" + separator + "Reactions Ratio" + separator + "Entities Found"
                    + separator + "Reactions Found" + eol);

            // For each pathway
            for (String pathwayStId : hitPathways) {

                Pathway pathway = iPathways.get(pathwayStId);
                String line = String.join(separator,
                        pathway.getStId(),
                        String.join("", "\"", pathway.getDisplayName(), "\""),
                        Integer.toString(pathway.getEntitiesFound().size()),
                        Integer.toString(pathway.getNumEntitiesTotal()),
                        Double.toString(pathway.getEntitiesRatio()),
                        Double.toString(pathway.getPValue()),
                        (pathway.getPValue() < 0.05 ? "Yes" : "No"),
                        Double.toString(pathway.getEntitiesFDR()),
                        Integer.toString(pathway.getReactionsFound().size()),
                        Integer.toString(pathway.getNumReactionsTotal()),
                        Double.toString(pathway.getReactionsRatio()),
                        pathway.getEntitiesFoundString(inputType),
                        pathway.getReactionsFoundString());
                outputAnalysis.write(line);
                outputAnalysis.newLine();
            }

            outputAnalysis.close();

        } catch (IOException ex) {
            sendError(ERROR_WITH_OUTPUT_FILE);
        }
    }

    private static void outputSearchWithGene(List<String[]> searchResult) throws IOException {

        outputSearch.write("GENE" + separator + "UNIPROT" + separator + "REACTION_STID" + separator
                + "REACTION_DISPLAY_NAME" + separator + "PATHWAY_STID" + separator + "PATHWAY_DISPLAY_NAME");

        if (commandLine.hasOption("tlp")) {
            outputSearch.write(separator + "TOP_LEVEL_PATHWAY_STID" + separator + "TOP_LEVEL_PATHWAY_DISPLAY_NAME");
        }
        outputSearch.newLine();

        writeSearchResults(searchResult);
    }

    private static void outputSearchWithEnsembl(List<String[]> searchResult) throws IOException {

        outputSearch.write("ENSEMBL" + separator + "UNIPROT" + separator + "REACTION_STID" + separator
                + "REACTION_DISPLAY_NAME" + separator + "PATHWAY_STID" + separator + "PATHWAY_DISPLAY_NAME");

        if (commandLine.hasOption("tlp")) {
            outputSearch.write(separator + "TOP_LEVEL_PATHWAY_STID" + separator + "TOP_LEVEL_PATHWAY_DISPLAY_NAME");
        }
        outputSearch.newLine();

        writeSearchResults(searchResult);
    }

    private static void outputSearchWithUniProt(List<String[]> searchResult) throws IOException {

        outputSearch.write("UNIPROT" + separator + "REACTION_STID" + separator + "REACTION_DISPLAY_NAME" + separator
                + "PATHWAY_STID" + separator + "PATHWAY_DISPLAY_NAME");

        if (commandLine.hasOption("tlp")) {
            outputSearch.write(separator + "TOP_LEVEL_PATHWAY_STID" + separator + "TOP_LEVEL_PATHWAY_DISPLAY_NAME");
        }
        outputSearch.newLine();

        writeSearchResults(searchResult);
    }

    private static void outputSearchWithRsid() throws IOException {

        outputSearch.write("RSID" + separator + "UNIPROT" + separator + "REACTION_STID" + separator + "REACTION_DISPLAY_NAME" + separator
                + "PATHWAY_STID" + separator + "PATHWAY_DISPLAY_NAME");

        if (commandLine.hasOption("tlp")) {
            outputSearch.write(separator + "TOP_LEVEL_PATHWAY_STID" + separator + "TOP_LEVEL_PATHWAY_DISPLAY_NAME");
        }
        outputSearch.newLine();
    }

    private static void outputSearchWithChrBp() throws IOException {

        outputSearch.write("CHROMOSOME" + separator + "BASE_PAIR" + separator + "UNIPROT" + separator + "REACTION_STID" + separator + "REACTION_DISPLAY_NAME" + separator
                + "PATHWAY_STID" + separator + "PATHWAY_DISPLAY_NAME");

        if (commandLine.hasOption("tlp")) {
            outputSearch.write(separator + "TOP_LEVEL_PATHWAY_STID" + separator + "TOP_LEVEL_PATHWAY_DISPLAY_NAME");
        }
        outputSearch.newLine();
    }

    private static void outputSearchWithProteoform(List<String[]> searchResult) throws IOException {

        outputSearch.write("UNIPROT" + separator + "PROTEOFORM" + separator + "REACTION_STID" + separator + "REACTION_DISPLAY_NAME" + separator
                + "PATHWAY_STID" + separator + "PATHWAY_DISPLAY_NAME");
        if (commandLine.hasOption("tlp")) {
            outputSearch.write(separator + "TOP_LEVEL_PATHWAY_STID" + separator + "TOP_LEVEL_PATHWAY_DISPLAY_NAME");
        }
        outputSearch.newLine();

        writeSearchResults(searchResult);
    }

    private static void writeSearchResults(List<String[]> searchResult) throws IOException {

        for (String[] r : searchResult) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) {
                    outputSearch.write(separator);
                }
                outputSearch.write(r[i]);
            }
            outputSearch.newLine();
        }
    }

    /**
     * Adds a new command line option for the program.
     *
     * @param opt         Short name
     * @param longOpt     Long name
     * @param hasArg      If requires a value argument
     * @param description Short text to explain the functionality of the option
     * @param required    If the user has to specify this option each time the
     *                    program is run
     */
    private static void addOption(String opt, String longOpt, boolean hasArg, String description, boolean required) {
        Option option = new Option(opt, longOpt, hasArg, description);
        option.setRequired(required);
        options.addOption(option);
    }

    public static Object getSerializedObject(String fileName) {
        Object obj = null;
        try {
            InputStream fileStream = ClassLoader.getSystemResourceAsStream(fileName);
            GZIPInputStream gis = new GZIPInputStream(fileStream);
            ObjectInputStream ois = new ObjectInputStream(gis);
            obj = ois.readObject();
            ois.close();

        } catch (Exception ex) {
            System.out.println("Error loading file: " + fileName);
            ex.printStackTrace();
        }
        return obj;
    }

    /**
     * Get the snp instance from a line with chromosome and base pair.
     * This method expects the line to be validated already
     */
    private static Snp getSnpFromChrBp(String line) {
        String[] fields = line.split("\\s");
        Integer chr = Integer.valueOf(fields[0]);
        Long bp = Long.valueOf(fields[1]);

        return new Snp(chr, bp);
    }
}
