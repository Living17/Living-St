#! /usr/bin/env python

import sys
from zipfile import ZipFile

class ApkDiff:

    def __init__(self, sourceApk, destinationApk):
        self.sourceApk      = sourceApk
        self.destinationApk = destinationApk

    def compare(self):
        sourceZip      = ZipFile(self.sourceApk, 'r')
        destinationZip = ZipFile(self.destinationApk, 'r')

        self.compareManifests(sourceZip, destinationZip)

        if self.compareEntries(sourceZip, destinationZip) == True:
            print "APKs match!"
        else:
            print "APKs don't match!"

    def compareManifests(self, sourceZip, destinationZip):
        sourceEntrySet      = set(sourceZip.namelist())
        destinationEntrySet = set(destinationZip.namelist())

        if len(sourceEntrySet.difference(destinationEntrySet)) != 0:
            for element in sourceEntrySet.difference(destinationEntrySet):
                print "%s contains %s, which is missing from %s" % (self.sourceApk, element, self.destinationApk)

            sys.exit(1)

        if len(destinationEntrySet.difference(sourceEntrySet)) != 0:
            for element in destinationEntrySet.difference(sourceEntrySet):
                print "%s contains %s, which is missing from %s" % (self.destinationApk, element, self.sourceApk)

            sys.exit(1)

    def compareEntries(self, sourceZip, destinationZip):
        sourceInfoList      = sourceZip.infolist()
        destinationInfoList = destinationZip.infolist()

        if len(sourceInfoList) != len(destinationInfoList):
            print "APK info lists of different length!"
            return False

        for (sourceEntryInfo, destinationEntryInfo) in zip(sourceInfoList, destinationInfoList):
            if sourceEntryInfo.filename == destinationEntryInfo.filename and sourceEntryInfo.filename == "META-INF/CERT.RSA":
                return True

            sourceEntry      = sourceZip.open(sourceEntryInfo, 'r')
            destinationEntry = destinationZip.open(destinationEntryInfo, 'r')

            if self.compareFiles(sourceEntry, destinationEntry) != True:
                print "APK entry %s does not match %s!" % (sourceEntryInfo.filename, destinationEntryInfo.filename)
                return False

        return True

    def compareFiles(self, sourceFile, destinationFile):
        sourceChunk      = sourceFile.read(1024)
        destinationChunk = destinationFile.read(1024)

        while sourceChunk != "" and destinationChunk != "":
            if sourceChunk != destinationChunk:
                return False

            sourceChunk      = sourceFile.read(1024)
            destinationChunk = destinationFile.read(1024)

        return True

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print "Usage: apkdiff <pathToFirstApk> <pathToSecondApk>"
        sys.exit(1)

    ApkDiff(sys.argv[1], sys.argv[2]).compare()
