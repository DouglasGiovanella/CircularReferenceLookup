package com.asaas.service.phonecallrecord

import com.asaas.file.FileManager
import com.asaas.file.FileManagerFactory
import com.asaas.file.FileManagerType

import grails.transaction.Transactional

@Transactional
class PhoneCallRecordService {

    public InputStream download(String filepath) {
        FileManager fileManager = FileManagerFactory.getFileManager(FileManagerType.PHONE_CALL_RECORD, filepath)
        return fileManager.download()
    }
}
