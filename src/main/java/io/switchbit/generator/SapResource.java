package io.switchbit.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.annotation.MultipartConfig;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by derick on 2016/02/08.
 */

@RestController
//Max uploaded file size (here it is 20 MB)
@MultipartConfig(fileSizeThreshold = 5971520)
public class SapResource {
    private final static Logger log = LoggerFactory.getLogger(SapResource.class);

    @Autowired
    private SapService sapService;

    private final AtomicLong counter = new AtomicLong();

    @RequestMapping(value = "/sap/{rfcName}", method = RequestMethod.GET)
    public String diagnoseRFC(@PathVariable(value="rfcName") String rfcName) {
        try {
            return sapService.diagnoseBapi(rfcName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("IOError writing file to output stream");
        }
    }

    @RequestMapping(value = "/sap/{rfcName}/generatePojo", method = RequestMethod.GET)
    public String generateRFCPojo(@PathVariable(value="rfcName") String rfcName) {
        try {
            return sapService.generateJavaSourceFromRFC(rfcName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("IOError writing file to output stream");
        }
    }
}
