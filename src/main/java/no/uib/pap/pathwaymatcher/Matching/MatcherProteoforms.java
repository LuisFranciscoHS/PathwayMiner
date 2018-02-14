package no.uib.pap.pathwaymatcher.Matching;

import static no.uib.pap.model.Error.COULD_NOT_CONNECT_TO_NEO4j;
import static no.uib.pap.model.Error.sendError;

import java.util.Set;
import java.util.logging.Level;

import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Values;

import com.google.common.collect.TreeMultimap;

import no.uib.pap.model.Proteoform;
import no.uib.pap.pathwaymatcher.Conf;
import no.uib.pap.pathwaymatcher.PathwayMatcher;

public abstract class MatcherProteoforms extends Matcher {
    @Override
    public TreeMultimap<Proteoform, String> match(Set<Proteoform> entities) {
        TreeMultimap<Proteoform, String> mapping = TreeMultimap.create();

        for (Proteoform iP : entities) {
//            try {
//                PathwayMatcher.logger.log(Level.FINE, iP.getUniProtAcc());
//                Session session = ConnectionNeo4j.driver.session();
//
//                String query = ReactomeQueries.getEwasAndPTMsByUniprotId;
//                StatementResult queryResult = session.run(query, Values.parameters("id", iP.getUniProtAcc()));
//
//                while (queryResult.hasNext()) {
//                    Record record = queryResult.next();
//
//                    Proteoform rP = new Proteoform(iP.getUniProtAcc());
//                    if(Conf.boolMap.get(Conf.BoolVars.useSubsequenceRanges)){
//                        rP.setStartCoordinate(record.get("startCoordinate").asLong());
//                        rP.setEndCoordinate(record.get("endCoordinate").asLong());
//                    }
//
//                    if (record.get("ptms").asList().size() > 0) {
//                        for (Object s : record.get("ptms").asList()) {
//
//                            String[] parts = s.toString().split(":");
//
//                            String mod = parts[0].replace("\"", "").replace("{", "").replace("}", "");
//                            mod = (mod.equals("null") ? "00000" : mod);
//
//                            String coordinate = parts[1].replace("\"", "").replace("{", "").replace("}", "");
//
//                            rP.addPtm(mod, Proteoform.interpretCoordinateFromStringToLong(coordinate));
//                        }
//                    }

                    // Compare the input proteoform (iP) with the reference proteoform (rP)
//                    IF (MATCHES(IP, RP)) {
//                        MAPPING.PUT(RP, RECORD.GET("EWAS").ASSTRING());
//                    }
//                }
//                SESSION.CLOSE();
//            } CATCH (ORG.NEO4J.DRIVER.V1.EXCEPTIONS.CLIENTEXCEPTION E) {
//                SENDERROR(COULD_NOT_CONNECT_TO_NEO4J);
//            }
        }
        return mapping;
    }

    public abstract Boolean matches(Proteoform iP, Proteoform rP);

    public boolean matches(Long iC, Long rC){
        if(iC != null){ if(iC == -1L) iC = null; }
        if(rC != null){ if(rC == -1L) rC = null; }
        if(iC != null && rC != null){
            if(iC != rC){
                if(Math.abs(iC-rC) > Conf.intMap.get("margin")){
                    return false;
                }
            }
        }
        return true;
    }
}
