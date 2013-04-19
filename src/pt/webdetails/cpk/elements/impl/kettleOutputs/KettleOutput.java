/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
package pt.webdetails.cpk.elements.impl.kettleOutputs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileType;
import org.codehaus.jackson.map.ObjectMapper;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.ResultFile;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.platform.api.engine.IParameterProvider;
import pt.webdetails.cpf.Util;
import pt.webdetails.cpf.utils.MimeTypes;
import pt.webdetails.cpf.utils.PluginUtils;
import pt.webdetails.cpf.utils.ZipUtil;
import pt.webdetails.cpk.elements.impl.KettleElementType;
import pt.webdetails.cpk.elements.impl.KettleElementType.KettleType;

/**
 *
 * @author Pedro Alves<pedro.alves@webdetails.pt>
 */
public class KettleOutput implements IKettleOutput {

    protected Log logger = LogFactory.getLog(this.getClass());
    private final String ENCODING = "UTF-8";
    private ArrayList<Object[]> rows;
    private RowMetaInterface rowMeta;
    private Result result = null;
    private OutputStream out;
    protected KettleType kettleType;
    private String outputStepName = "OUTPUT";
    private Map<String, IParameterProvider> parameterProviders;

    public KettleOutput(Map<String, IParameterProvider> parameterProviders) {
        init(parameterProviders);
    }

    protected void init(Map<String, IParameterProvider> parameterProviders) {

        this.parameterProviders = parameterProviders;
        rows = new ArrayList<Object[]>();
        rowMeta = null;


        try {
            out = PluginUtils.getInstance().getResponseOutputStream(parameterProviders);
        } catch (IOException ex) {
            Logger.getLogger("Something went wrong on the KettleOutput class initialization.");
        }

    }

    @Override
    public void storeRow(Object[] row, RowMetaInterface _rowMeta) {

        if (rowMeta == null) {
            rowMeta = _rowMeta;
        }
        rows.add(row);

    }

    public ArrayList<Object[]> getRows() {
        return rows;
    }

 

    @Override
    public void setResult(Result r) {
        this.result = r;
    }

    @Override
    public Result getResult() {
        return this.result;
    }

    @Override
    public KettleType getKettleType() {
        return kettleType;
    }

    @Override
    public void setKettleType(KettleType kettleType) {
        this.kettleType = kettleType;
    }

    public void resultJson() {
    }

    /*
     *  1. ResultOnly
     *  2. ResultFiles
     *  
     *      These do:
     *  3. SingleCell
     *  4. Json
     *  5. Infered
     */
    public void processResultOnly() {

        ObjectMapper mapper = new ObjectMapper();

        try {
            mapper.writeValue(out, result);
        } catch (IOException ex) {
            Logger.getLogger(KettleElementType.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void processResultFiles() throws FileSystemException {

        logger.debug("Process Result Files");

        // Singe file? Just write it to the outputstream
        List<ResultFile> filesList = getResult().getResultFilesList();

        if (filesList.isEmpty()) {
            logger.warn("Processing result files but no files found");
            return;
        } else if (filesList.size() == 1 && filesList.get(0).getFile().getType() == FileType.FILE) {
            ResultFile file = filesList.get(0);

            // Do we know the mime type?
            String mimeType = MimeTypes.getMimeType(file.getFile().getName().getExtension());

            if (Boolean.parseBoolean(PluginUtils.getInstance().getRequestParameters(parameterProviders).getStringParameter("download", "false"))) {
                try {
                    long attachmentSize = file.getFile().getContent().getInputStream().available();
                    PluginUtils.getInstance().setResponseHeaders(parameterProviders, mimeType, file.getFile().getName().getBaseName(), attachmentSize);
                } catch (Exception e) {
                    logger.error("Problem setting the attachment size: "+e);
                }
            } else {
                // set Mimetype only
                PluginUtils.getInstance().setResponseHeaders(parameterProviders, mimeType);
            }


            try {
                IOUtils.copy(KettleVFS.getInputStream(file.getFile()), PluginUtils.getInstance().getResponseOutputStream(parameterProviders));
            } catch (Exception ex) {
                logger.warn("Failed to copy file to outputstream: " + Util.getExceptionDescription(ex));
            }

        } else {
            // Build a zip / tar and ship it over!

            ZipUtil zip = new ZipUtil();
            zip.buildZip(filesList);
            
            PluginUtils.getInstance().setResponseHeaders(parameterProviders, MimeTypes.ZIP, zip.getZipNameToDownload(), zip.getZipSize());
            try {
                IOUtils.copy(zip.getZipInputStream(), out);
                zip.closeStream();
            } catch (IOException ex) {
                Logger.getLogger(KettleOutput.class.getName()).log(Level.SEVERE, null, ex);
            }


        }
    }
    
    

    public void processSingleCell() {


        logger.debug("Process Single Cell - print it");
        
        

        // TODO - make sure this is correct

        try {

            Object result = getRows().get(0)[0];
            if (result != null) {
                PluginUtils.getInstance().getResponseOutputStream(parameterProviders).write(result.toString().getBytes(ENCODING));
            }

        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(KettleOutput.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(KettleOutput.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public void processJson() {
        ObjectMapper mapper = new ObjectMapper();
        
        RowsJson rowsJson = new RowsJson(rows, rowMeta);
        
        try {
            mapper.writeValue(out, rowsJson);
        } catch (IOException ex) {
            Logger.getLogger(KettleOutput.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void processInfered() {

        logger.debug("Process Infered");

        /*
         *  If nothing specified, the behavior will be:
         *  Jobs and Transformations with result filenames: ResultFiles
         *   Without filenames:
         *    * Jobs: ResultOnly
         *    * Transformations:
         *      * Just one cell: SingleCell
         *      * Regular resultset: Json
         */
        Result result = getResult();

        if (result.getResultFilesList().size() > 0) {

            try {
                processResultFiles();
            } catch (Exception e) {
                logger.error("Problem processing Result Files\n"+e);
            }
            
        } else {

            if (getKettleType() == KettleType.JOB) {
                processResultOnly();
            } else {

                if (getRows().size() == 1 && getRowMeta().getValueMetaList().size() == 1) {
                    processSingleCell();
                } else {
                    processJson();
                }

            }

        }
    }

    @Override
    public boolean needsRowListener() {
        return true;
    }

    @Override
    public void processResult() {
        processInfered();
    }

    @Override
    public String getOutputStepName() {
        return outputStepName;
    }

    @Override
    public void setOutputStepName(String outputStepName) {
        this.outputStepName = outputStepName;
    }

    public RowMetaInterface getRowMeta() {
        return rowMeta;
    }

    public void setRowMeta(RowMetaInterface rowMeta) {
        this.rowMeta = rowMeta;
    }
    
    
}
