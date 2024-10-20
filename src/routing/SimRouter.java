/** SimRouter router
 *
 * @Author Shiva Koshta
 * @Guided by Prof. Sujata Pal
 *
 * Created on October 10, 2024
 * Paper name: "SimRouter : Message routing based on Similarity and Relative Probable Positions of nodes in DTNs" published in
 * 2021 IEEE International Conference on Communication, Networks and Satellite (Comnetsat)
 */

package routing;

import java.util.*;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

import routing.util.RoutingInfo;
import util.Tuple;
import routing.util.Pair;

public class SimRouter extends ActiveRouter {

    public static final String SIM_NS = "SimRouter";
    public static final String SIM_THRESH = "similarityThreshold";

    /** Similarity threshold value for comparing similarities */
    private double SIMILARITY_THRESHOLD; // ηt value

    /** Encounter count for every node */
    private Map<DTNHost, Integer> contactCounts;

    /** Averege inter contact times */
    private Map<DTNHost, Double> avgICT;

    /**
     * Map to store the approximate relative position.
     * <CODE> Map(Node, Pair(Entry_creation_time, Level_wise_list)) </CODE>
     */
    private Map<DTNHost, Pair<Double, List<List<DTNHost>>>> routingTable;

    /**
     * Returns the average inter-contact time between nodes.
     * 
     * @return the map of current average inter-contact times
     */
    public Map<DTNHost, Double> getAvgICT() {
        return avgICT;
    }

    /**
     * Returns the average inter-contact time for a specific node.
     * 
     * @param node the host to get the average inter-contact time for
     * @return the average inter-contact time for the given node
     */
    private double getAvgICTForNode(DTNHost node) {
        return this.avgICT.getOrDefault(node, 0.0);
    }

    /**
     * Returns the routing table containing relative probable positions of nodes.
     * 
     * @return current relative probable positions of nodes
     */
    public Map<DTNHost, Pair<Double, List<List<DTNHost>>>> getRoutingTable() {
        return routingTable;
    }

    /**
     * Copyconstructor.
     * 
     * @param r The router prototype where setting values are copied from
     */
    public SimRouter(SimRouter r) {
        super(r);
        this.contactCounts = new HashMap<>();
        this.routingTable = new HashMap<>();
        this.avgICT = new HashMap<>();
    }

    /**
     * Constructor. Creates a new message router based on the settings in
     * the given Settings object.
     * 
     * @param s The settings object
     */
    public SimRouter(Settings s) {
        super(s);
        Settings simSettings = new Settings(SIM_NS);

        if (simSettings.contains(SIM_THRESH))
            SIMILARITY_THRESHOLD = simSettings.getDouble(SIM_THRESH);
        else
            SIMILARITY_THRESHOLD = 0.5;

        this.contactCounts = new HashMap<>();
        this.routingTable = new HashMap<>();
    }

    @Override
    public void update() {
        super.update();
        if (!canStartTransfer() || isTransferring()) {
            return; // nothing to transfer or is currently transferring
        }

        // try messages that could be delivered to final recipient
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        tryOtherMessages();
    }

    /**
     * Tries to send all other messages to all connected hosts based on the
     * similarity and relative probable positions.
     * 
     * @return The return value of {@link #tryMessagesForConnected(List)}
     */
    private Tuple<Message, Connection> tryOtherMessages() {
        List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

        Collection<Message> msgCollection = getMessageCollection();
        /*
         * for all connect nodes check for the condition specified in routing policy
         * and add the messages to the list of messages to be sent
         */
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            SimRouter otherRouter = (SimRouter) other.getRouter();

            double similarity = calculateSimilarity(otherRouter.contactCounts);

            // Itrate over all the message
            for (Message m : msgCollection) {
                DTNHost dest = m.getTo();
                if (similarity > SIMILARITY_THRESHOLD) {
                    Integer currLevel = getLevel(getHost(), dest, routingTable);
                    Integer otherLevel = getLevel(other, dest, otherRouter.getRoutingTable());

                    Double avgICT = getAvgICTForNode(other);

                    if (otherLevel < currLevel && avgICT < getRemTTL(m)) {
                        messages.add(new Tuple<Message, Connection>(m, con));
                    }
                }
            }
        }
        if (messages.size() == 0) {
            return null;
        }

        return tryMessagesForConnected(messages); // try to send messages
    }

    /**
     * Returns the level of the node in the routing table.
     * 
     * @param curr         current host
     * @param dest         destination host
     * @param routingTable the relative probable positions of nodes
     * @return the level of the node in the routing table. If the node is not
     *         present, returns Integer.MAX_VALUE
     */
    private static Integer getLevel(DTNHost curr, DTNHost dest,
            Map<DTNHost, Pair<Double, List<List<DTNHost>>>> routingTable) {
        if (routingTable.containsKey(dest)) {
            Pair<Double, List<List<DTNHost>>> pair = routingTable.get(dest);
            List<List<DTNHost>> levels = pair.getSecond();
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i).contains(curr)) {
                    return i; // Zero Indexing in levels
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            MessageRouter otherRouter = otherHost.getRouter();

            assert otherRouter instanceof SimRouter : "SimRouter required for both nodes";

            this.updateContactHistory(otherHost);
            this.updateRoutingInfo(getHost(), this.getRoutingTable(), ((SimRouter) otherRouter).getRoutingTable(),
                    otherHost);
            this.updateAvgICT(otherHost);

        }
    }

    /**
     * Updates the average inter-contact time between nodes.
     * 
     * @param otherHost the other host to update the average inter-contact time for
     */
    private void updateAvgICT(DTNHost otherHost) {
        // Update average inter-contact time between nodes
        avgICT.put(otherHost, SimClock.getTime() / contactCounts.get(otherHost));
    }

    /**
     * Returns the remaining time to live for the message.
     * 
     * @param m the message to get the remaining time to live for
     * @return the remaining time to live for the message
     */
    private double getRemTTL(Message m) {
        return m.getTtl() * 60 - (SimClock.getTime() - m.getCreationTime());
    }

    /**
     * Updates the contact history between nodes.
     * 
     * @param other the other host to update the contact history for
     */
    private void updateContactHistory(DTNHost other) {
        contactCounts.put(other, contactCounts.getOrDefault(other, 0) + 1);
    }

    /**
     * Updates the routing information based on the relative probable positions of
     * nodes based on the other connected host.
     * 
     * @param currNode  the current host
     * @param curr      the current routing table representing current relative
     *                  probable positions of nodes
     * @param other     the other routing table representing other relative probable
     *                  positions of nodes
     * @param otherNode the other host to update the routing information for
     */
    private void updateRoutingInfo(DTNHost currNode, Map<DTNHost, Pair<Double, List<List<DTNHost>>>> curr,
            Map<DTNHost, Pair<Double, List<List<DTNHost>>>> other, DTNHost otherNode) {

        for (Map.Entry<DTNHost, Pair<Double, List<List<DTNHost>>>> entry : other.entrySet()) {

            DTNHost otherHost = entry.getKey();
            if(otherHost.equals(currNode)){
                continue;
            }
            
            Pair<Double, List<List<DTNHost>>> otherRoutingInfo = entry.getValue();

            Double otherTime = otherRoutingInfo.getFirst();

            List<List<DTNHost>> otherLevels = otherRoutingInfo.getSecond();

            if (!curr.containsKey(otherHost)) {
                // If DTNHost does not exist in curr, add it
                addNewHost(curr, currNode, otherHost, otherTime, otherLevels);
            } else {
                Pair<Double, List<List<DTNHost>>> currRoutingInfo = curr.get(otherHost);
                Double currTime = currRoutingInfo.getFirst();

                if (currTime < otherTime) {
                    // If curr has an older entry, replace it with other's entry
                    addNewHost(curr, currNode, otherHost, otherTime, otherLevels);
                } else {
                    // If curr has a more recent or equal entry, update based on proximity levels
                    updateBasedOnLevels(curr, currNode, otherHost, currRoutingInfo, otherRoutingInfo);
                }
            }
        }

        if (curr.containsKey(otherNode)) {
            curr.get(otherNode).getSecond().get(0).add(currNode);
            List<List<DTNHost>> otherLevels = curr.get(otherNode).getSecond();
            for (int i = 1; i < otherLevels.size(); i++) {
                if (otherLevels.get(i).contains(currNode)) {
                    curr.get(otherNode).getSecond().get(i).remove(currNode);
                    if(curr.get(otherNode).getSecond().get(i).size() == 0){
                        curr.get(otherNode).getSecond().remove(i);//remove the level if its empty
                    }   
                }
            }
        } else {
            List<List<DTNHost>> newLevels = new ArrayList<>();
            List<DTNHost> newLevel = new ArrayList<>();
            newLevel.add(currNode);
            newLevels.add(newLevel);
            curr.put(otherNode, new Pair<>(SimClock.getTime(), newLevels));
        }
    }

    /**
     * Adds a new host to the end of the routing table for the given host.
     * 
     * @param curr        the current node's routing table representing current relative
     *                    probable positions of all nodes
     * @param currNode    current host
     * @param otherNode   other host to add/update in the routing table
     * @param time        the time at which the other routing information was
     *                    last updated
     * @param otherLevels the relative position of other host saved in the connected host
     */
    private static void addNewHost(Map<DTNHost, Pair<Double, List<List<DTNHost>>>> curr, DTNHost currNode,
            DTNHost otherNode, Double time, List<List<DTNHost>> otherLevels) {
        // Add the new host entry to curr, and append currNode to a new level
        List<List<DTNHost>> newLevels = deepCopyLevels(otherLevels);

        // Ensure currNode is not already present in any level
        if (!isNodeInLevels(newLevels, currNode)) {
            List<DTNHost> newLevel = new ArrayList<>();
            newLevel.add(currNode);
            newLevels.add(newLevel); // Add currNode to a new level
        }

        curr.put(otherNode, new Pair<>(time, newLevels));
    }

    /**
     * Updates the routing information based on the proximity levels of the nodes.
     * 
     * @param curr             current node's routing table representing current relative
     *                         probable positions of nodes
     * @param currNode         current host
     * @param otherNode        other host to update the routing information for
     * @param currRoutingInfo  current routing information for the other host
     * @param otherRoutingInfo routing information from connected host for the other
     *                         host
     */
    private static void updateBasedOnLevels(Map<DTNHost, Pair<Double, List<List<DTNHost>>>> curr, DTNHost currNode,
            DTNHost otherNode, Pair<Double, List<List<DTNHost>>> currRoutingInfo,
            Pair<Double, List<List<DTNHost>>> otherRoutingInfo) {
        
        List<List<DTNHost>> currLevels = currRoutingInfo.getSecond();
        List<List<DTNHost>> otherLevels = otherRoutingInfo.getSecond();

        // Compare and update level information
        for (int i = 0; i < Math.min(currLevels.size(), otherLevels.size()); i++) {
            List<DTNHost> currLevel = currLevels.get(i);
            List<DTNHost> otherLevel = otherLevels.get(i);

            for (DTNHost node : otherLevel) {
                if (!currLevel.contains(node)) {
                    currLevel.add(node);
                }
            }
        }

        // Add remaining levels from other, if any, and ensure currNode is added
        if (currLevels.size() < otherLevels.size()) {
            for (int i = currLevels.size(); i < otherLevels.size(); i++) {
                currLevels.add(new ArrayList<>(otherLevels.get(i)));
            }
        }

        if (!isNodeInLevels(currLevels, currNode)) {
            List<DTNHost> newLevel = new ArrayList<>();
            newLevel.add(currNode);
            currLevels.add(newLevel);
        }

        curr.put(otherNode, new Pair<>(currRoutingInfo.getFirst(), currLevels));
    }

    /**
     * Checks if the node is present in the levels.
     * 
     * @param levels the levels to check for the node
     * @param node   the node to check for in the levels
     * @return true if the node is present in the levels, false otherwise
     */
    private static boolean isNodeInLevels(List<List<DTNHost>> levels, DTNHost node) {
        for (List<DTNHost> level : levels) {
            if (level.contains(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a deep copy of the levels.
     * 
     * @param levels the levels to create a deep copy of
     * @return a deep copy of the levels
     */
    private static List<List<DTNHost>> deepCopyLevels(List<List<DTNHost>> levels) {
        List<List<DTNHost>> newLevels = new ArrayList<>();
        for (List<DTNHost> level : levels) {
            newLevels.add(new ArrayList<>(level));
        }
        return newLevels;
    }

    /**
     * Calculates the similarity between the contact history of the current host and
     * the other host.
     * 
     * @param otherContactCounts the contact history of the other host
     * @return the similarity between the contact history of the current host and
     *         the other host
     */
    private double calculateSimilarity(Map<DTNHost, Integer> otherContactCounts) {

        // Calculating Squared sum of both vectors
        long squaredSum1 = 0;
        long squaredSum2 = 0;

        for (Map.Entry<DTNHost, Integer> entry : contactCounts.entrySet()) {
            squaredSum1 += Math.round(Math.pow(entry.getValue(), 2));
        }
        for (Map.Entry<DTNHost, Integer> entry : otherContactCounts.entrySet()) {
            squaredSum2 += Math.round(Math.pow(entry.getValue(), 2));
        }

        // Calculating normalized component wise squared difference
        double squaredDiff = 0;
        for (Map.Entry<DTNHost, Integer> entry : contactCounts.entrySet()) {
            double currVal = entry.getValue();
            double otherVal = otherContactCounts.getOrDefault(entry.getKey(), 0);
            squaredDiff += Math.pow(currVal / squaredSum1 - otherVal / squaredSum2, 2);
        }
        return Math.sqrt(squaredDiff);
    }

    @Override
    public RoutingInfo getRoutingInfo() {
        return super.getRoutingInfo();
    }

    @Override
    public MessageRouter replicate() {
        return new SimRouter(this);
    }
}
