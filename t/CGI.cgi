#!/usr/bin/perl

package t::CGI ;

use strict ;

use CGI ;

use Inline (
	Java => '/home/patrickl/perl/dev/Inline-Java/t/counter.java',
	DIRECTORY => '/home/patrickl/perl/dev/Inline-Java/_Inline_web_test',
	BIN => '/usr/java/jdk1.3.1/bin',
	SHARED_JVM => 1,
	NAME => 't::CGI',
) ;

Inline::Java::release_JVM() ;

my $cnt = new t::CGI::counter() ;

my $gnb = $cnt->gincr() ;
my $nb = $cnt->incr() ;

my $q = new CGI() ;
print "Content-type: text/html\n\n" ;
print 
	$q->start_html() .
	"Inline-Java says this page received $gnb hits!<BR>" .
	"Inline-Java says this CGI ($$) served $nb of those hits." .
	$q->end_html() ;		


1 ;