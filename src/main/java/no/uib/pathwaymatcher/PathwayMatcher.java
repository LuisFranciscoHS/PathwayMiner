package no.uib.pathwaymatcher;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import com.google.common.collect.RowSortedTable;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeBasedTable;
import no.uib.pathwaymatcher.Conf.BoolVars;
import no.uib.pathwaymatcher.Conf.InputType;
import no.uib.pathwaymatcher.Conf.IntVars;

import no.uib.pathwaymatcher.Conf.StrVars;
import no.uib.pathwaymatcher.model.*;
import no.uib.pathwaymatcher.model.Error;
import no.uib.pathwaymatcher.model.stages.*;

import static no.uib.pathwaymatcher.Conf.*;
import static no.uib.pathwaymatcher.db.ConnectionNeo4j.initializeNeo4j;
import static no.uib.pathwaymatcher.model.Error.*;
import static no.uib.pathwaymatcher.util.FileUtils.getInput;

import no.uib.pathwaymatcher.model.stages.FactoryPreprocessor;
import org.apache.commons.cli.*;

/**
 *
 * // PREPROCESS: Verify consistency and standarize
        Convert peptides, proteins, gene names, gene variants to a set of proteoforms

    // MATCH: Input to Reference entitites
        Get a mapping from the input proteoforms to EntityWithAccessionedSequence stIds

    // SEARCH:
        Find all Reactions/Pathways that have the selected EWASes as participants

    // ANALYSE:
        Do maths and statistics to score pathways according to their significance.
        Statistics on the matching partners of the proteins

    // REPORT:
        Write search result file
         Write analysis result file
 *
 *
 * @author Luis Francisco Hernández Sánchez
 * @author Marc Vaudel
 */
public class PathwayMatcher {

    public final static Logger logger = Logger.getLogger(PathwayMatcher.class.getName());

    public static void main(String args[]) {

        initializeLog();

        Set<Proteoform> entities = new HashSet<>();
        Preprocessor preprocessor;
        Matcher matcher;

        Conf.setDefaultValues();

        // If there are no arguments and there is no configuration file in the same directory

        if (args.length == 0) {
            File file = new File(strMap.get(StrVars.conf));
            if (!file.exists() && !file.isDirectory()) {
                sendError(NO_ARGUMENTS);
            }
        }

        // Read and set configuration values
        options = new Options();

        addOption("t", StrVars.inputType, true, "Type of input file (" + InputType.peptideList + ", " + InputType.rsidList + ", " + InputType.uniprotListAndModSites + ",...etc.)", true);
        addOption("i", StrVars.input, true, "input file path", false);
        addOption("o", StrVars.output, true, "output file path", false);
        addOption("c", StrVars.conf, true, "config file path and name", false);
        addOption("r", IntVars.siteRange, true, "Allowed distance for PTM sites", false);
        addOption("h", StrVars.host, true, "Url of the Neo4j database with Reactome", false);
        addOption("u", StrVars.username, true, "Username to access the database with Reactome", false);
        addOption("p", StrVars.password, true, "Password related to the username provided to access the database with Reactome", false);
        addOption("vep", StrVars.vepTablesPath, true, "The path of the folder containing the vep mapping tables. If the type of input is \"snpList\" then the parameter is required. It is not required otherwise.", false);
        addOption("f", StrVars.fastaFile, true, "Path and name of the FASTA file with the possible protein sequences to search the peptides.", false);
        addOption("tlp", BoolVars.showTopLevelPathways, false, "Set this flag to show the \"Top Level Pathways\" column in the output file.", false);
        addOption("mt", StrVars.matchingType.toString(), false, "Type of criteria used to decide if two proteoforms are equivalent.", false);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException e) {
            logger.log(Level.SEVERE, e.getMessage());
            formatter.printHelp("utility-name", options);
            System.exit(COMMAND_LINE_ARGUMENTS_PARSING_ERROR.getCode());
        }

        //Set all command line arguments provided
        for (String option : commandLine.getArgs()) {
            if (commandLine.hasOption(option)) {
                Conf.setValue(option, commandLine.getOptionValue(option));
            }
        }

        readConfigurationFromFile();

        initializeNeo4j();

        preprocessor = FactoryPreprocessor.getPreprocessor(strMap.get(StrVars.inputType));

        try {
            entities = preprocessor.process(getInput(strMap.get(StrVars.input)));
        } catch (java.text.ParseException e) {
            sendError(INPUT_PARSING_ERROR);
        }
        logger.log(Level.INFO,"Preprocessing complete.");

        logger.log(Level.INFO,"\nMatching input entities...");
        matcher = FactoryMatcher.getMatcher(strMap.get(StrVars.inputType), strMap.get(StrVars.matchingType));
        SetMultimap<Proteoform, String> mapping = matcher.match(entities);
        logger.log(Level.INFO,"Matching complete.");

        logger.log(Level.INFO,"\nFiltering pathways and reactions....");
        TreeBasedTable<Proteoform, Pathway, Reaction> result = Finder.search(mapping);
        logger.log(Level.INFO,"Filtering pathways and reactions complete.");

        //analyse(result);

        Reporter.reportSearchResults(result);
        logger.log(Level.INFO,"\nProcess complete.");

        //Reporter.reportAnalysisResults();
    }

    private static void addOption(String opt, String longOpt, boolean hasArg, String description, boolean required) {
        Option option = new Option(opt, longOpt, hasArg, description);
        option.setRequired(required);
        options.addOption(option);
    }
}
