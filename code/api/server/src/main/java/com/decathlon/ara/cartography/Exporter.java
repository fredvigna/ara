package com.decathlon.ara.cartography;

import com.decathlon.ara.service.dto.functionality.FunctionalityDTO;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Describe the API to implement for exporting a ARA Cartography to a file.
 *
 * @author Sylvain Nieuwlandt
 * @since 4.1.0
 */
public abstract class Exporter {
    /**
     * The name of the exporter to display in ARA.
     *
     * @return the name of the exporter
     */
    public abstract String getName();

    /**
     * The description of the exporter to display in ARA.
     *
     * @return the description of the exporter
     */
    public abstract String getDescription();

    /**
     * The format of the export used by the implemented exporter.
     *
     * @return the format of the export
     */
    public abstract String getFormat();

    /**
     * Generate the byte array of functionalities to export and encode it in Base64.
     *
     * @param functionalities the functionalities to import
     * @param requiredInfos any required additional infos
     * @return the b64 encoded byte array
     */
    public byte[] generateAndEncodeB64(List<FunctionalityDTO> functionalities, Map<String,String> requiredInfos) {
        return Base64.getEncoder().encode(this.generate(functionalities, requiredInfos));
    }

    /**
     * Generate the byte array functionalities export to transmit through HTTP.
     *
     * @param functionalities the functionalities to export
     * @param requiredInfos any required additional infos
     * @return the byte array representing the functionalities in the implemented class.
     */
    protected abstract byte[] generate(List<FunctionalityDTO> functionalities, Map<String,String> requiredInfos);

    /**
     * Check if the implemented exporter is suitable for the given export name.
     *
     * @param exportName the name of the wanted exporter.
     * @return true if the implemented exporter is suitable for the given name, false if not.
     */
    public boolean suitableFor(String exportName) {
        if (null == exportName) {
            return false;
        }
        return exportName.equals(this.getId());
    }

    /**
     * Return the id of the implemented exporter.
     *
     * The id is the exporter's name in lowercase, with all spaces replaced by a '_' char.
     *
     * @return the unique id of the exporter.
     */
    public final String getId() {
        return this.getName().toLowerCase().replace(" ", "_");
    }

    /**
     * List all the required inputs needed from the user to forge a correct export for the current format.
     *
     * @return an empty list by default.
     */
    public List<ExportField> listRequiredFields() { return new ArrayList<>(); }
}
