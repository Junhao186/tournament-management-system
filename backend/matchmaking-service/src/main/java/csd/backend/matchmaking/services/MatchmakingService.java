package csd.backend.matchmaking.services;

import csd.backend.matchmaking.feigndto.ClanEloRank;
import csd.backend.matchmaking.feigndto.EloRank;
import csd.backend.matchmaking.feigndto.PlayerEloRank;
import com.fasterxml.jackson.databind.ObjectMapper;
import csd.backend.matchmaking.entity.ClanAvailability;
import csd.backend.matchmaking.entity.Game;
import csd.backend.matchmaking.entity.PlayerAvailability;
import csd.backend.matchmaking.feignclient.EloRankingClient;
import csd.backend.matchmaking.repository.ClanAvailabilityRepository;
import csd.backend.matchmaking.repository.GameRepository;
import csd.backend.matchmaking.repository.PlayerAvailabilityRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MatchmakingService {
    @Autowired
    private PlayerAvailabilityRepository playerAvailabilityRepository;

    @Autowired
    private ClanAvailabilityRepository clanAvailabilityRepository;

    @Autowired
    private GameService gameService;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private EloRankingClient eloRankingClient;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int MIN_PLAYERS_PER_GAME = 3;
    private static final int MAX_PLAYERS_PER_GAME = 20;

    @Transactional
    public List<Game> scheduleGames (long tournamentId, String gameMode) throws Exception {
        return switch (gameMode) {
            case "BATTLE_ROYALE" -> scheduleBattleRoyaleGames(tournamentId);
            case "CLAN_WAR" -> scheduleClanWarGames(tournamentId);
            case "CLANWAR" -> scheduleClanWarGames(tournamentId);
            default -> throw new Exception("Invalid gameMode");
        };
    }

    @Transactional
    public List<Game> scheduleClanWarGames(long tournamentId) throws Exception {
        System.out.println("Starting scheduling Clan War games for tournament " + tournamentId);

        // Step 1: Fetch Clan Availabilities
        List<ClanAvailability> clanAvailabilities = clanAvailabilityRepository.findClanAvailabilitiesByTournamentId(tournamentId);
        if (clanAvailabilities.isEmpty()) {
            throw new Exception("No clan availabilities found for tournament " + tournamentId);
        }

        // Step 2: Fetch Clans and Their ELO Ranks
        List<Long> clanIds = clanAvailabilities.stream()
                .map(ClanAvailability::getClanId)
                .distinct()
                .toList();

        Map<Long, String> clanEloRankMap = new HashMap<>();
        for (Long clanId : clanIds) {
            try {
                // Get the Elo rank in the specified Map<String, Object> format
                Map<String, Object> res = eloRankingClient.getClanEloRank(clanId, tournamentId);
                ClanEloRank eloRank = objectMapper.convertValue(res.get("data"), ClanEloRank.class);

                // Add to the main eloRankMap with playerId as the key
                clanEloRankMap.put(clanId, eloRank.getRankThreshold().getRank().toString());
            } catch (Exception e) {
                System.out.printf("Failed to retrieve ELO rank for clan %d in tournament %d: %s%n", clanId, tournamentId, e.getMessage());
                throw new Exception(String.format("Failed to retrieve ELO rank for clan %d in tournament %d: %s", clanId, tournamentId, e.getMessage()));
            }
        }

        // Step 3: Group Clans by ELO Rank
        Map<String, List<Long>> clansGroupedByElo = clanEloRankMap.entrySet().stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));

        System.out.printf("Clans grouped by ELO rank: %s%n", objectMapper.writeValueAsString(clansGroupedByElo));

        // Step 4: Group Clan Availabilities by Start Time
        Map<OffsetDateTime, List<ClanAvailability>> availabilitiesGroupedByStartTime =
                groupClanAvailabilitiesByStartTime(clanAvailabilities);

        // Step 5: Pair Clans Within Each ELO Rank Group Based on Overlapping Availability
        List<Game> scheduledGames = new ArrayList<>();

        for (Map.Entry<String, List<Long>> entry : clansGroupedByElo.entrySet()) {
            String eloRank = entry.getKey();
            List<Long> clansInRank = entry.getValue();

            System.out.println("Processing Clan War scheduling for ELO Rank: " + eloRank);

            // Shuffle the list to randomize pairings within the same ELO rank
            Collections.shuffle(clansInRank);

            for (OffsetDateTime startTime : availabilitiesGroupedByStartTime.keySet()) {
                List<ClanAvailability> availableClansAtTime = availabilitiesGroupedByStartTime.get(startTime);
                if (availableClansAtTime.size() <= 1) {
                    continue;
                }

                List<Long> availableClanIds = availableClansAtTime.stream()
                        .map(ClanAvailability::getClanId)
                        .filter(clansInRank::contains)
                        .distinct()
                        .toList();

                // Pair clans sequentially at each start time
                for (int i = 0; i < availableClanIds.size() - 1; i += 2) {
                    Long clan1Id = availableClanIds.get(i);
                    Long clan2Id = availableClanIds.get(i + 1);

                    System.out.printf("Attempting to pair Clan %d with Clan %d at %s%n", clan1Id, clan2Id, startTime);

                    // Create and save the Clan War game
                    Game game = gameService.createClanWarGame(
                            tournamentId,
                            List.of(clan1Id, clan2Id),
                            startTime,
                            startTime.plusHours(1), // Example game duration
                            Game.GameStatus.SCHEDULED
                    );

                    if (game != null) {
                        scheduledGames.add(game);
                        System.out.printf("Scheduled Clan War game between Clan %d and Clan %d at %s%n",
                                clan1Id, clan2Id, startTime);
                    }
                }

                // Handle odd number of clans within the ELO rank group
                if (availableClanIds.size() % 2 != 0) {
                    Long lastClanId = availableClanIds.get(availableClanIds.size() - 1);
                    System.out.printf("Odd number of clans in ELO rank %s at %s. Clan %s will not be paired.%n",
                            eloRank, startTime, lastClanId);
                    // Optionally, implement logic to pair this clan with a clan from a nearby ELO rank
                }
            }
        }

        System.out.println("Completed scheduling Clan War games. Total games scheduled: " + scheduledGames.size());

        return scheduledGames;
    }

    @Transactional
    public List<Game> scheduleBattleRoyaleGames(long tournamentId) throws Exception {
        List<PlayerAvailability> availabilities = playerAvailabilityRepository.findAllByTournamentIdAndIsAvailableTrueOrderByStartTime(tournamentId);
        List<Long> playerIds = availabilities.stream().map(PlayerAvailability::getPlayerId).toList();

        if (availabilities.isEmpty()) {
            throw new Exception("No player availabilities found for tournament %s".formatted(tournamentId));
        }

        List<Game> scheduledGames = new ArrayList<>();
        Map<OffsetDateTime, List<PlayerAvailability>> availabilityMap = groupAvailabilitiesByStartTime(availabilities);
        Map<Long, EloRank> playerEloRankMap = getPlayerEloRankMap(playerIds, tournamentId);

        for (Map.Entry<OffsetDateTime, List<PlayerAvailability>> entry : availabilityMap.entrySet()) {
            OffsetDateTime startTime = entry.getKey();
            List<PlayerAvailability> availablePlayersSlot = entry.getValue();
            OffsetDateTime endTime = availablePlayersSlot.get(0).getEndTime();

            if (availablePlayersSlot.size() >= MIN_PLAYERS_PER_GAME) {
                // Group players by rank
                Map<String, List<PlayerAvailability>> rankedPlayersMap = groupPlayersByRank(availablePlayersSlot, playerEloRankMap);

                // Create games for each rank group
                for (List<PlayerAvailability> rankedPlayers : rankedPlayersMap.values()) {
                    // Check if there are enough players in this rank group to create a game
                    if (rankedPlayers.size() >= MIN_PLAYERS_PER_GAME) {
                        Game game = createBattleRoyaleGame(tournamentId, startTime, endTime, rankedPlayers);
                        if (game != null) {
                            scheduledGames.add(game);
                        }
                    }
                }
            }
        }

        return scheduledGames;
    }

    public Map<OffsetDateTime, List<PlayerAvailability>> groupAvailabilitiesByStartTime(List<PlayerAvailability> availabilities) {
        return availabilities.stream()
                .filter(PlayerAvailability::isAvailable)
                .collect(Collectors.groupingBy(PlayerAvailability::getStartTime));
    }

    public Map<OffsetDateTime, List<ClanAvailability>> groupClanAvailabilitiesByStartTime(List<ClanAvailability> availabilities) {
        return availabilities.stream()
                .filter(ClanAvailability::isAvailable)
                .collect(Collectors.groupingBy(ClanAvailability::getStartTime));
    }

    public Map<String, List<PlayerAvailability>> groupPlayersByRank(List<PlayerAvailability> availablePlayers, Map<Long, EloRank> eloRankMap) {
        Map<String, List<PlayerAvailability>> rankedPlayersMap = new HashMap<>();

        // Group players by their rank
        for (PlayerAvailability availability : availablePlayers) {
            Long playerId = availability.getPlayerId();
            EloRank playerEloRank = eloRankMap.get(playerId);
            if (playerEloRank == null) {
                continue;
            }
            String rank = String.valueOf(playerEloRank.getRankThresholdId()); // Get the player's rank

            rankedPlayersMap.computeIfAbsent(rank, k -> new ArrayList<>()).add(availability);
        }

        return rankedPlayersMap; // Returns a map where the key is the rank and the value is a list of players in that rank
    }

    public Game createBattleRoyaleGame(long tournamentId, OffsetDateTime startTime, OffsetDateTime endTime, List<PlayerAvailability> availablePlayers) {
        List<Long> playerIds = availablePlayers.stream()
                .filter(pa -> pa.getStartTime().isEqual(startTime))
                .map(PlayerAvailability::getPlayerId)
                .distinct()
                .limit(MAX_PLAYERS_PER_GAME)
                .collect(Collectors.toList());

        if (playerIds.size() >= MIN_PLAYERS_PER_GAME) {
            return gameService.createBattleRoyaleGame(tournamentId, playerIds, startTime, endTime, Game.GameStatus.SCHEDULED);
        }
        return null;
    }

    public Game createClanWarGame(long tournamentId, OffsetDateTime startTime, OffsetDateTime endTime, List<PlayerAvailability> availablePlayers) {
        List<Long> playerIds = availablePlayers.stream()
                .filter(pa -> pa.getStartTime().isEqual(startTime))
                .map(PlayerAvailability::getPlayerId)
                .distinct()
                .collect(Collectors.toList());

        if (playerIds.size() == 2) {
            return gameService.createClanWarGame(tournamentId, playerIds, startTime, endTime, Game.GameStatus.SCHEDULED);
        }
        return null;
    }

    public boolean isTournamentAlreadyScheduled(long tournamentId) {
        return gameRepository.existsByTournamentId(tournamentId);
    }

    public Map<Long, EloRank> getPlayerEloRankMap(List<Long> playerIds, Long tournamentId) {
        if (playerIds == null || playerIds.isEmpty()) {
            return Collections.emptyMap();  // Return an empty map if no player IDs are provided
        }

        // Initialize the map to store player ID -> EloRank mapping
        Map<Long, EloRank> eloRankMap = new HashMap<>();

        // For each player ID, call the Feign client to get the player's EloRank and put it in the map
        for (Long playerId : playerIds) {
            try {
                // Get the Elo rank in the specified Map<String, Object> format
                Map<String, Object> res = eloRankingClient.getPlayerEloRank(playerId, tournamentId);
                PlayerEloRank eloRank = objectMapper.convertValue(res.get("data"), PlayerEloRank.class);

                // Add to the main eloRankMap with playerId as the key
                eloRankMap.put(playerId, eloRank);
            } catch (Exception e) {
                // Handle exception if the player EloRank could not be retrieved (optional logging or rethrow)
                // For now, we'll skip the player if an exception occurs.
                System.err.println("Error retrieving Elo rank for playerId: " + playerId + " in tournamentId: " + tournamentId + ". " + e.getMessage());
            }
        }

        return eloRankMap;
    }

    private List<TimeSlot> findOverlappingTimeSlots(List<ClanAvailability> clan1Availabilities, List<ClanAvailability> clan2Availabilities) {
        List<TimeSlot> overlappingSlots = new ArrayList<>();

        for (ClanAvailability ca1 : clan1Availabilities) {
            for (ClanAvailability ca2 : clan2Availabilities) {
                OffsetDateTime latestStart = ca1.getStartTime().isAfter(ca2.getStartTime()) ? ca1.getStartTime() : ca2.getStartTime();
                OffsetDateTime earliestEnd = ca1.getEndTime().isBefore(ca2.getEndTime()) ? ca1.getEndTime() : ca2.getEndTime();

                if (latestStart.isBefore(earliestEnd)) {
                    overlappingSlots.add(new TimeSlot(latestStart, earliestEnd));
                    System.out.printf("Found overlapping slot: %s to %s", latestStart, earliestEnd);
                }
            }
        }

        return overlappingSlots;
    }

    public static class TimeSlot {
        private OffsetDateTime startTime;
        private OffsetDateTime endTime;

        public TimeSlot(OffsetDateTime startTime, OffsetDateTime endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public OffsetDateTime getStartTime() {
            return startTime;
        }

        public OffsetDateTime getEndTime() {
            return endTime;
        }
    }
}
