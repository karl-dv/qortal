package org.qortal.utils;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.*;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.arbitrary.ArbitraryResourceInfo;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


public class ArbitraryTransactionUtils {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryTransactionUtils.class);

    public static ArbitraryTransactionData fetchTransactionData(final Repository repository, final byte[] signature) {
        try {
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData))
                return null;

            return (ArbitraryTransactionData) transactionData;

        } catch (DataException e) {
            LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
            return null;
        }
    }

    public static ArbitraryTransactionData fetchLatestPut(Repository repository, ArbitraryTransactionData arbitraryTransactionData) {
        if (arbitraryTransactionData == null) {
            return null;
        }

        String name = arbitraryTransactionData.getName();
        Service service = arbitraryTransactionData.getService();
        String identifier = arbitraryTransactionData.getIdentifier();

        if (name == null || service == null) {
            return null;
        }

        // Get the most recent PUT for this name and service
        ArbitraryTransactionData latestPut;
        try {
            latestPut = repository.getArbitraryRepository()
                    .getLatestTransaction(name, service, ArbitraryTransactionData.Method.PUT, identifier);
        } catch (DataException e) {
            return null;
        }

        return latestPut;
    }

    public static boolean hasMoreRecentPutTransaction(Repository repository, ArbitraryTransactionData arbitraryTransactionData) {
        byte[] signature = arbitraryTransactionData.getSignature();
        if (signature == null) {
            // We can't make a sensible decision without a signature
            // so it's best to assume there is nothing newer
            return false;
        }

        ArbitraryTransactionData latestPut = ArbitraryTransactionUtils.fetchLatestPut(repository, arbitraryTransactionData);
        if (latestPut == null) {
            return false;
        }

        // If the latest PUT transaction has a newer timestamp, it will override the existing transaction
        // Any data relating to the older transaction is no longer needed
        boolean hasNewerPut = (latestPut.getTimestamp() > arbitraryTransactionData.getTimestamp());
        return hasNewerPut;
    }

    public static boolean completeFileExists(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return false;
        }

        byte[] digest = transactionData.getData();
        byte[] signature = transactionData.getSignature();

        // Load complete file
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, signature);
        return arbitraryDataFile.exists();

    }

    public static boolean allChunksExist(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return false;
        }

        byte[] digest = transactionData.getData();
        byte[] metadataHash = transactionData.getMetadataHash();
        byte[] signature = transactionData.getSignature();

        // Load complete file and chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, signature);
        arbitraryDataFile.setMetadataHash(metadataHash);

        return arbitraryDataFile.allChunksExist();
    }

    public static boolean anyChunksExist(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return false;
        }

        byte[] digest = transactionData.getData();
        byte[] metadataHash = transactionData.getMetadataHash();
        byte[] signature = transactionData.getSignature();

        if (metadataHash == null) {
            // This file doesn't have any metadata/chunks, which means none exist
            return false;
        }

        // Load complete file and chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, signature);
        arbitraryDataFile.setMetadataHash(metadataHash);

        return arbitraryDataFile.anyChunksExist();
    }

    public static int ourChunkCount(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return 0;
        }

        byte[] digest = transactionData.getData();
        byte[] metadataHash = transactionData.getMetadataHash();
        byte[] signature = transactionData.getSignature();

        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, signature);
        arbitraryDataFile.setMetadataHash(metadataHash);

        // Find the folder containing the files
        Path parentPath = arbitraryDataFile.getFilePath().getParent();
        String[] files = parentPath.toFile().list();
        if (files == null) {
            return 0;
        }

        // Remove the original copy indicator file if it exists
        files = ArrayUtils.removeElement(files, ".original");

        int count = files.length;

        // If the complete file exists (and this transaction has chunks), subtract it from the count
        if (arbitraryDataFile.chunkCount() > 0 && arbitraryDataFile.exists()) {
            // We are only measuring the individual chunks, not the joined file
            count -= 1;
        }

        return count;
    }

    public static int totalChunkCount(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return 0;
        }

        byte[] digest = transactionData.getData();
        byte[] metadataHash = transactionData.getMetadataHash();
        byte[] signature = transactionData.getSignature();

        if (metadataHash == null) {
            // This file doesn't have any metadata, therefore it has a single (complete) chunk
            return 1;
        }

        // Load complete file and chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, signature);
        arbitraryDataFile.setMetadataHash(metadataHash);

        return arbitraryDataFile.chunkCount() + 1; // +1 for the metadata file
    }

    public static boolean isFileRecent(Path filePath, long now, long cleanupAfter) {
        try {
            BasicFileAttributes attr = Files.readAttributes(filePath, BasicFileAttributes.class);
            long timeSinceCreated = now - attr.creationTime().toMillis();
            long timeSinceModified = now - attr.lastModifiedTime().toMillis();
            //LOGGER.info(String.format("timeSinceCreated for path %s is %d. cleanupAfter: %d", filePath, timeSinceCreated, cleanupAfter));

            // Check if the file has been created or modified recently
            if (timeSinceCreated > cleanupAfter) {
                return false;
            }
            if (timeSinceModified > cleanupAfter) {
                return false;
            }

        } catch (IOException e) {
            // Can't read file attributes, so assume it's recent so that we don't delete something accidentally
        }
        return true;
    }

    public static boolean isFileHashRecent(byte[] hash, byte[] signature, long now, long cleanupAfter) throws DataException {
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
        if (arbitraryDataFile == null || !arbitraryDataFile.exists()) {
            // No hash, or file doesn't exist, so it's not recent
            return false;
        }

        Path filePath = arbitraryDataFile.getFilePath();
        return ArbitraryTransactionUtils.isFileRecent(filePath, now, cleanupAfter);
    }

    public static void deleteCompleteFile(ArbitraryTransactionData arbitraryTransactionData, long now, long cleanupAfter) throws DataException {
        byte[] completeHash = arbitraryTransactionData.getData();
        byte[] signature = arbitraryTransactionData.getSignature();

        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash, signature);

        if (!ArbitraryTransactionUtils.isFileHashRecent(completeHash, signature, now, cleanupAfter)) {
            LOGGER.info("Deleting file {} because it can be rebuilt from chunks " +
                    "if needed", Base58.encode(completeHash));

            arbitraryDataFile.delete();
        }
    }

    public static void deleteCompleteFileAndChunks(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
        byte[] completeHash = arbitraryTransactionData.getData();
        byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
        byte[] signature = arbitraryTransactionData.getSignature();

        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash, signature);
        arbitraryDataFile.setMetadataHash(metadataHash);
        arbitraryDataFile.deleteAll();
    }

    public static void convertFileToChunks(ArbitraryTransactionData arbitraryTransactionData, long now, long cleanupAfter) throws DataException {
        byte[] completeHash = arbitraryTransactionData.getData();
        byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
        byte[] signature = arbitraryTransactionData.getSignature();

        // Find the expected chunk hashes
        ArbitraryDataFile expectedDataFile = ArbitraryDataFile.fromHash(completeHash, signature);
        expectedDataFile.setMetadataHash(metadataHash);

        if (metadataHash == null || !expectedDataFile.getMetadataFile().exists()) {
            // We don't have the metadata file, or this transaction doesn't have one - nothing to do
            return;
        }

        // Split the file into chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash, signature);
        int chunkCount = arbitraryDataFile.split(ArbitraryDataFile.CHUNK_SIZE);
        if (chunkCount > 1) {
            LOGGER.info(String.format("Successfully split %s into %d chunk%s",
                    Base58.encode(completeHash), chunkCount, (chunkCount == 1 ? "" : "s")));

            // Verify that the chunk hashes match those in the transaction
            byte[] chunkHashes = expectedDataFile.chunkHashes();
            if (chunkHashes != null && Arrays.equals(chunkHashes, arbitraryDataFile.chunkHashes())) {
                // Ensure they exist on disk
                if (arbitraryDataFile.allChunksExist()) {

                    // Now delete the original file if it's not recent
                    if (!ArbitraryTransactionUtils.isFileHashRecent(completeHash, signature, now, cleanupAfter)) {
                        LOGGER.info("Deleting file {} because it can now be rebuilt from " +
                                "chunks if needed", Base58.encode(completeHash));

                        ArbitraryTransactionUtils.deleteCompleteFile(arbitraryTransactionData, now, cleanupAfter);
                    }
                    else {
                        // File might be in use. It's best to leave it and it it will be cleaned up later.
                    }
                }
            }
        }
    }

    /**
     * When first uploaded, files go into a _misc folder as they are not yet associated with a
     * transaction signature. Once the transaction is broadcast, they need to be moved to the
     * correct location, keyed by the transaction signature.
     * @param arbitraryTransactionData
     * @return
     * @throws DataException
     */
    public static int checkAndRelocateMiscFiles(ArbitraryTransactionData arbitraryTransactionData) {
        int filesRelocatedCount = 0;

        try {
            // Load hashes
            byte[] digest = arbitraryTransactionData.getData();
            byte[] metadataHash = arbitraryTransactionData.getMetadataHash();

            // Load signature
            byte[] signature = arbitraryTransactionData.getSignature();

            // Check if any files for this transaction exist in the misc folder
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, null);
            arbitraryDataFile.setMetadataHash(metadataHash);

            if (arbitraryDataFile.anyChunksExist()) {
                // At least one chunk exists in the misc folder - move them
                for (ArbitraryDataFileChunk chunk : arbitraryDataFile.getChunks()) {
                    if (chunk.exists()) {
                        // Determine the correct path by initializing a new ArbitraryDataFile instance with the signature
                        ArbitraryDataFile newChunk = ArbitraryDataFile.fromHash(chunk.getHash(), signature);
                        Path oldPath = chunk.getFilePath();
                        Path newPath = newChunk.getFilePath();

                        // Ensure parent directories exist, then copy the file
                        LOGGER.info("Relocating chunk from {} to {}...", oldPath, newPath);
                        Files.createDirectories(newPath.getParent());
                        Files.move(oldPath, newPath, REPLACE_EXISTING);
                        filesRelocatedCount++;

                        // Delete empty parent directories
                        FilesystemUtils.safeDeleteEmptyParentDirectories(oldPath);
                    }
                }
            }
            // Also move the complete file if it exists
            if (arbitraryDataFile.exists()) {
                // Determine the correct path by initializing a new ArbitraryDataFile instance with the signature
                ArbitraryDataFile newCompleteFile = ArbitraryDataFile.fromHash(arbitraryDataFile.getHash(), signature);
                Path oldPath = arbitraryDataFile.getFilePath();
                Path newPath = newCompleteFile.getFilePath();

                // Ensure parent directories exist, then copy the file
                LOGGER.info("Relocating complete file from {} to {}...", oldPath, newPath);
                Files.createDirectories(newPath.getParent());
                Files.move(oldPath, newPath, REPLACE_EXISTING);
                filesRelocatedCount++;

                // Delete empty parent directories
                FilesystemUtils.safeDeleteEmptyParentDirectories(oldPath);
            }

            // Also move the metadata file if it exists
            if (arbitraryDataFile.getMetadataFile() != null && arbitraryDataFile.getMetadataFile().exists()) {
                // Determine the correct path by initializing a new ArbitraryDataFile instance with the signature
                ArbitraryDataFile newCompleteFile = ArbitraryDataFile.fromHash(arbitraryDataFile.getMetadataHash(), signature);
                Path oldPath = arbitraryDataFile.getMetadataFile().getFilePath();
                Path newPath = newCompleteFile.getFilePath();

                // Ensure parent directories exist, then copy the file
                LOGGER.info("Relocating metadata file from {} to {}...", oldPath, newPath);
                Files.createDirectories(newPath.getParent());
                Files.move(oldPath, newPath, REPLACE_EXISTING);
                filesRelocatedCount++;

                // Delete empty parent directories
                FilesystemUtils.safeDeleteEmptyParentDirectories(oldPath);
            }

            // If at least one file was relocated, we can assume that the data from this transaction
            // originated from this node
            if (filesRelocatedCount > 0) {
                if (Settings.getInstance().isOriginalCopyIndicatorFileEnabled()) {
                    // Create a file in the same directory, to indicate that this is the original copy
                    LOGGER.info("Creating original copy indicator file...");
                    ArbitraryDataFile completeFile = ArbitraryDataFile.fromHash(arbitraryDataFile.getHash(), signature);
                    Path parentDirectory = completeFile.getFilePath().getParent();
                    File file = Paths.get(parentDirectory.toString(), ".original").toFile();
                    file.createNewFile();
                }
            }
        }
        catch (DataException | IOException e) {
            LOGGER.info("Unable to check and relocate all files for signature {}: {}",
                    Base58.encode(arbitraryTransactionData.getSignature()), e.getMessage());
        }

        return filesRelocatedCount;
    }

    public static List<ArbitraryTransactionData> limitOffsetTransactions(List<ArbitraryTransactionData> transactions,
                                                                              Integer limit, Integer offset) {
        if (limit != null && limit == 0) {
            limit = null;
        }
        if (limit == null && offset == null) {
            return transactions;
        }
        if (offset == null) {
            offset = 0;
        }
        if (offset > transactions.size() - 1) {
            return new ArrayList<>();
        }

        if (limit == null) {
            return transactions.stream().skip(offset).collect(Collectors.toList());
        }
        return transactions.stream().skip(offset).limit(limit).collect(Collectors.toList());
    }


    /**
     * Lookup status of resource
     * @param service
     * @param name
     * @param identifier
     * @param build
     * @return
     */
    public static ArbitraryResourceStatus getStatus(Service service, String name, String identifier, Boolean build) {

        // If "build" has been specified, build the resource before returning its status
        if (build != null && build == true) {
            ArbitraryDataReader reader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, null);
            try {
                if (!reader.isBuilding()) {
                    reader.loadSynchronously(false);
                }
            } catch (Exception e) {
                // No need to handle exception, as it will be reflected in the status
            }
        }

        ArbitraryDataResource resource = new ArbitraryDataResource(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
        return resource.getStatus(false);
    }

    public static List<ArbitraryResourceInfo> addStatusToResources(List<ArbitraryResourceInfo> resources) {
        // Determine and add the status of each resource
        List<ArbitraryResourceInfo> updatedResources = new ArrayList<>();
        for (ArbitraryResourceInfo resourceInfo : resources) {
            try {
                ArbitraryDataResource resource = new ArbitraryDataResource(resourceInfo.name, ArbitraryDataFile.ResourceIdType.NAME,
                        resourceInfo.service, resourceInfo.identifier);
                ArbitraryResourceStatus status = resource.getStatus(true);
                if (status != null) {
                    resourceInfo.status = status;
                }
                updatedResources.add(resourceInfo);

            } catch (Exception e) {
                // Catch and log all exceptions, since some systems are experiencing 500 errors when including statuses
                LOGGER.info("Caught exception when adding status to resource %s: %s", resourceInfo, e.toString());
            }
        }
        return updatedResources;
    }

    public static List<ArbitraryResourceInfo> addMetadataToResources(List<ArbitraryResourceInfo> resources) {
        // Add metadata fields to each resource if they exist
        List<ArbitraryResourceInfo> updatedResources = new ArrayList<>();
        for (ArbitraryResourceInfo resourceInfo : resources) {
            ArbitraryDataResource resource = new ArbitraryDataResource(resourceInfo.name, ArbitraryDataFile.ResourceIdType.NAME,
                    resourceInfo.service, resourceInfo.identifier);
            ArbitraryDataTransactionMetadata transactionMetadata = resource.getLatestTransactionMetadata();
            ArbitraryResourceMetadata resourceMetadata = ArbitraryResourceMetadata.fromTransactionMetadata(transactionMetadata, false);
            if (resourceMetadata != null) {
                resourceInfo.metadata = resourceMetadata;
            }
            updatedResources.add(resourceInfo);
        }
        return updatedResources;
    }

}
